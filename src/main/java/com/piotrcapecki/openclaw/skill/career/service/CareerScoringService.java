package com.piotrcapecki.openclaw.skill.career.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piotrcapecki.openclaw.core.ai.OpenRouterClient;
import com.piotrcapecki.openclaw.skill.career.domain.JobOffer;
import com.piotrcapecki.openclaw.skill.career.domain.OfferScore;
import com.piotrcapecki.openclaw.skill.career.domain.UserProfile;
import com.piotrcapecki.openclaw.skill.career.dto.ScoreResultDto;
import com.piotrcapecki.openclaw.skill.career.repository.JobOfferRepository;
import com.piotrcapecki.openclaw.skill.career.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class CareerScoringService {

    private final OpenRouterClient openRouterClient;
    private final JobOfferRepository jobOfferRepository;
    private final UserProfileRepository userProfileRepository;
    private final ObjectMapper objectMapper;

    public void scoreAllPending() {
        List<JobOffer> pending = jobOfferRepository.findByScore(OfferScore.PENDING_SCORE);
        if (pending.isEmpty()) {
            log.info("No pending offers to score");
            return;
        }

        UserProfile profile = userProfileRepository.findFirstByOrderByIdAsc()
                .orElseThrow(() -> new IllegalStateException(
                        "No user profile configured. Set one up via PATCH /api/career/profile"));

        try {
            String prompt = buildPrompt(profile, pending);
            String response = openRouterClient.complete(prompt);
            List<ScoreResultDto> results = parseResults(response);

            Map<String, JobOffer> offerMap = pending.stream()
                    .collect(Collectors.toMap(o -> o.getId().toString(), o -> o));

            for (ScoreResultDto result : results) {
                JobOffer offer = offerMap.get(result.offerId());
                if (offer == null) continue;
                try {
                    offer.setScore(OfferScore.valueOf(result.score()));
                    offer.setScoreReason(result.reason());
                    jobOfferRepository.save(offer);
                } catch (IllegalArgumentException e) {
                    log.warn("Unknown score '{}' for offer {}", result.score(), result.offerId());
                }
            }
        } catch (Exception e) {
            log.error("Scoring failed — offers remain PENDING_SCORE", e);
        }
    }

    private String buildPrompt(UserProfile profile, List<JobOffer> offers) {
        try {
            List<Map<String, String>> offerList = offers.stream().map(o -> Map.of(
                    "offerId",     o.getId().toString(),
                    "title",       nvl(o.getTitle()),
                    "company",     nvl(o.getCompany()),
                    "location",    nvl(o.getLocation()),
                    "description", nvl(o.getDescription())
            )).toList();

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
                        objectMapper.writeValueAsString(offerList)
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to build scoring prompt", e);
        }
    }

    private List<ScoreResultDto> parseResults(String content) throws Exception {
        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');
        if (start == -1 || end == -1)
            throw new RuntimeException("No JSON array found in OpenRouter response");
        return new ObjectMapper().readValue(content.substring(start, end + 1), new TypeReference<>() {});
    }

    private String nvl(String v) { return v != null ? v : ""; }
}
