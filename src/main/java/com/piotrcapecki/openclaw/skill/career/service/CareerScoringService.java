package com.piotrcapecki.openclaw.skill.career.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piotrcapecki.openclaw.core.ai.OpenRouterClient;
import com.piotrcapecki.openclaw.skill.career.domain.JobOffer;
import com.piotrcapecki.openclaw.skill.career.domain.OfferScore;
import com.piotrcapecki.openclaw.skill.career.domain.ScoringDecisionCache;
import com.piotrcapecki.openclaw.skill.career.domain.UserProfile;
import com.piotrcapecki.openclaw.skill.career.dto.ScoreResultDto;
import com.piotrcapecki.openclaw.skill.career.repository.JobOfferRepository;
import com.piotrcapecki.openclaw.skill.career.repository.ScoringDecisionCacheRepository;
import com.piotrcapecki.openclaw.skill.career.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class CareerScoringService {

    private static final String RULE_PREFILTER_SKIP_REASON = "Automatyczny filtr: brak zgodnosci z podstawowymi kryteriami profilu.";

    private final OpenRouterClient openRouterClient;
    private final JobOfferRepository jobOfferRepository;
    private final UserProfileRepository userProfileRepository;
    private final ScoringDecisionCacheRepository scoringDecisionCacheRepository;
    private final ObjectMapper objectMapper;

    private int maxOffersPerRun = 15;
    private boolean enableRulePrefilter = true;
    private boolean enableScoringCache = true;
    private String modelVersion = "unknown-model";

    @Value("${app.skills.career.scoring.max-offers-per-run:15}")
    void setMaxOffersPerRun(int maxOffersPerRun) {
        this.maxOffersPerRun = Math.max(1, maxOffersPerRun);
    }

    @Value("${app.skills.career.scoring.enable-rule-prefilter:true}")
    void setEnableRulePrefilter(boolean enableRulePrefilter) {
        this.enableRulePrefilter = enableRulePrefilter;
    }

    @Value("${app.skills.career.scoring.enable-cache:true}")
    void setEnableScoringCache(boolean enableScoringCache) {
        this.enableScoringCache = enableScoringCache;
    }

    @Value("${openrouter.model:unknown-model}")
    void setModelVersion(String modelVersion) {
        String normalizedModel = nvl(modelVersion).trim();
        this.modelVersion = normalizedModel.isBlank() ? "unknown-model" : normalizedModel;
    }

    public void scoreAllPending() {
        List<JobOffer> pending = jobOfferRepository.findByScore(OfferScore.PENDING_SCORE);
        if (pending.isEmpty()) {
            log.info("No pending offers to score");
            return;
        }

        UserProfile profile = userProfileRepository.findFirstByOrderByIdAsc()
                .orElseThrow(() -> new IllegalStateException(
                        "No user profile configured. Set one up via PATCH /api/career/profile"));

        List<JobOffer> selectedForModel = selectOffersForModel(pending, profile);
        if (selectedForModel.isEmpty()) {
            log.info("No offers selected for LLM scoring in this run");
            return;
        }

        String profileHash = buildProfileHash(profile);
        List<JobOffer> offersForModel = enableScoringCache
                ? applyCachedDecisions(selectedForModel, profileHash)
                : selectedForModel;

        if (offersForModel.isEmpty()) {
            log.info("All selected offers were scored from cache");
            return;
        }

        Map<String, String> cacheKeyByOfferId = offersForModel.stream()
                .collect(Collectors.toMap(
                        offer -> offer.getId().toString(),
                        offer -> buildCacheKey(offer, profileHash)));

        try {
            String prompt = buildPrompt(profile, offersForModel);
            String response = openRouterClient.complete(prompt);
            List<ScoreResultDto> results = parseResults(response);

            Map<String, JobOffer> offerMap = offersForModel.stream()
                    .collect(Collectors.toMap(o -> o.getId().toString(), o -> o));

            for (ScoreResultDto result : results) {
                JobOffer offer = offerMap.get(result.offerId());
                if (offer == null) {
                    log.warn("OpenRouter returned unknown offerId: {}", result.offerId());
                    continue;
                }
                try {
                    offer.setScore(OfferScore.valueOf(result.score()));
                    offer.setScoreReason(result.reason());
                    jobOfferRepository.save(offer);
                    upsertScoringCache(cacheKeyByOfferId.get(result.offerId()), offer, profileHash);
                } catch (IllegalArgumentException e) {
                    log.warn("Unknown score '{}' for offer {}", result.score(), result.offerId());
                }
            }
        } catch (Exception e) {
            log.error("Scoring failed — offers remain PENDING_SCORE", e);
        }
    }

    private List<JobOffer> selectOffersForModel(List<JobOffer> pending, UserProfile profile) {
        List<JobOffer> candidates = pending;

        if (enableRulePrefilter) {
            List<JobOffer> autoSkip = pending.stream()
                    .filter(offer -> !matchesProfileRules(offer, profile))
                    .toList();

            if (!autoSkip.isEmpty()) {
                autoSkip.forEach(offer -> {
                    offer.setScore(OfferScore.SKIP);
                    offer.setScoreReason(RULE_PREFILTER_SKIP_REASON);
                });
                jobOfferRepository.saveAll(autoSkip);
                log.info("Rule prefilter auto-skipped {} offers", autoSkip.size());
            }

            candidates = pending.stream()
                    .filter(offer -> matchesProfileRules(offer, profile))
                    .toList();
        }

        List<JobOffer> limited = candidates.stream()
                .sorted(Comparator.comparing(JobOffer::getFoundAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(maxOffersPerRun)
                .toList();

        if (candidates.size() > limited.size()) {
            log.info("Scoring limit reached: selected {} of {} matching offers", limited.size(), candidates.size());
        }

        return limited;
    }

    private List<JobOffer> applyCachedDecisions(List<JobOffer> selectedForModel, String profileHash) {
        List<String> keys = selectedForModel.stream()
                .map(offer -> buildCacheKey(offer, profileHash))
                .toList();

        Map<String, ScoringDecisionCache> cacheEntries = scoringDecisionCacheRepository.findByCacheKeyIn(keys)
                .stream()
                .collect(Collectors.toMap(ScoringDecisionCache::getCacheKey, cache -> cache));

        List<JobOffer> cachedOffers = new ArrayList<>();
        List<JobOffer> misses = new ArrayList<>();

        for (JobOffer offer : selectedForModel) {
            String cacheKey = buildCacheKey(offer, profileHash);
            ScoringDecisionCache cacheEntry = cacheEntries.get(cacheKey);
            if (cacheEntry == null) {
                misses.add(offer);
                continue;
            }

            offer.setScore(cacheEntry.getScore());
            offer.setScoreReason(cacheEntry.getReason());
            cachedOffers.add(offer);
        }

        if (!cachedOffers.isEmpty()) {
            jobOfferRepository.saveAll(cachedOffers);
            log.info("Applied scoring cache for {} offers", cachedOffers.size());
        }

        return misses;
    }

    private void upsertScoringCache(String cacheKey, JobOffer offer, String profileHash) {
        if (!enableScoringCache || cacheKey == null || cacheKey.isBlank()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        ScoringDecisionCache cache = scoringDecisionCacheRepository.findByCacheKey(cacheKey)
                .orElseGet(() -> ScoringDecisionCache.builder()
                        .cacheKey(cacheKey)
                        .createdAt(now)
                        .build());

        cache.setOfferId(offer.getId());
        cache.setProfileHash(profileHash);
        cache.setModelVersion(modelVersion);
        cache.setScore(offer.getScore());
        cache.setReason(offer.getScoreReason());
        cache.setUpdatedAt(now);
        scoringDecisionCacheRepository.save(cache);
    }

    private String buildProfileHash(UserProfile profile) {
        String payload = String.join("|",
                normalizeList(profile.getStack()),
                normalizeList(profile.getLevel()),
                normalizeList(profile.getLocations()),
                normalize(profile.getPreferences()));
        return sha256(payload);
    }

    private String buildCacheKey(JobOffer offer, String profileHash) {
        String offerFingerprint = sha256(String.join("|",
                normalize(offer.getExternalId()),
                normalize(offer.getTitle()),
                normalize(offer.getCompany()),
                normalize(offer.getLocation()),
                normalize(offer.getDescription())));

        return sha256(String.join("|", offerFingerprint, profileHash, normalize(modelVersion)));
    }

    private String normalizeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        return values.stream()
                .map(this::normalize)
                .filter(v -> !v.isBlank())
                .sorted()
                .collect(Collectors.joining(","));
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash scoring cache key", e);
        }
    }

    private boolean matchesProfileRules(JobOffer offer, UserProfile profile) {
        String haystack = normalize(String.join(" ",
                nvl(offer.getTitle()),
                nvl(offer.getCompany()),
                nvl(offer.getLocation()),
                nvl(offer.getDescription())));

        return matchesAny(profile.getStack(), haystack)
                && matchesAny(profile.getLevel(), haystack)
                && matchesAny(profile.getLocations(), haystack);
    }

    private boolean matchesAny(List<String> terms, String haystack) {
        if (terms == null || terms.isEmpty()) {
            return true;
        }

        return terms.stream()
                .map(this::normalize)
                .filter(term -> !term.isBlank())
                .anyMatch(haystack::contains);
    }

    private String buildPrompt(UserProfile profile, List<JobOffer> offers) {
        try {
            List<Map<String, String>> offerList = offers.stream().map(o -> Map.of(
                    "offerId", o.getId().toString(),
                    "title", nvl(o.getTitle()),
                    "company", nvl(o.getCompany()),
                    "location", nvl(o.getLocation()),
                    "description", nvl(o.getDescription()))).toList();

            return """
                    You are evaluating job offers for a candidate with the following profile:
                    - Stack: %s
                    - Level: %s
                    - Locations: %s
                    - Preferences: %s

                    For each offer below, return ONLY a JSON array. Each element must have:
                      "offerId" (string), "score" (STRONG | MEDIUM | SKIP), "reason" (Polish, max 100 chars)

                    No markdown, no explanation — only the JSON array.

                    Offers:
                    %s
                    """.formatted(
                    String.join(", ", profile.getStack()),
                    String.join(", ", profile.getLevel()),
                    String.join(", ", profile.getLocations()),
                    profile.getPreferences(),
                    objectMapper.writeValueAsString(offerList));
        } catch (Exception e) {
            throw new RuntimeException("Failed to build scoring prompt", e);
        }
    }

    private List<ScoreResultDto> parseResults(String content) throws Exception {
        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');
        if (start == -1 || end == -1)
            throw new RuntimeException("No JSON array found in OpenRouter response");
        return objectMapper.readValue(content.substring(start, end + 1), new TypeReference<>() {
        });
    }

    private String normalize(String value) {
        return nvl(value).toLowerCase();
    }

    private String nvl(String v) {
        return v != null ? v : "";
    }
}
