package com.piotrcapecki.openclaw.skill.career.api;

import com.piotrcapecki.openclaw.skill.career.domain.JobOffer;
import com.piotrcapecki.openclaw.skill.career.domain.OfferScore;
import com.piotrcapecki.openclaw.skill.career.domain.ScoringTelemetry;
import com.piotrcapecki.openclaw.skill.career.domain.ScrapeRun;
import com.piotrcapecki.openclaw.skill.career.dto.CareerDigestAckRequestDto;
import com.piotrcapecki.openclaw.skill.career.dto.CareerDigestAckResponseDto;
import com.piotrcapecki.openclaw.skill.career.dto.CareerDigestDto;
import com.piotrcapecki.openclaw.skill.career.dto.CareerDigestItemDto;
import com.piotrcapecki.openclaw.skill.career.dto.CareerDigestStatusDto;
import com.piotrcapecki.openclaw.skill.career.repository.JobOfferRepository;
import com.piotrcapecki.openclaw.skill.career.repository.ScoringTelemetryRepository;
import com.piotrcapecki.openclaw.skill.career.repository.ScrapeRunRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@RestController
@RequestMapping("/api/career/digest")
@RequiredArgsConstructor
@SecurityRequirement(name = "X-API-Key")
@Tag(name = "Digest", description = "Compact OpenClaw integration endpoints")
public class DigestController {

    private static final String CONTRACT_VERSION = "career-digest.v1";
    private static final int REASON_MAX_CHARS = 140;

    private final JobOfferRepository jobOfferRepository;
    private final ScrapeRunRepository scrapeRunRepository;
    private final ScoringTelemetryRepository scoringTelemetryRepository;

    @Operation(summary = "Get compact digest for OpenClaw")
    @GetMapping("/compact")
    public ResponseEntity<CareerDigestDto> getCompactDigest(
            @RequestParam(defaultValue = "5") int limitStrong,
            @RequestParam(defaultValue = "5") int limitMedium,
            @RequestParam(defaultValue = "true") boolean onlyUnsent,
            @RequestParam(required = false) String sinceCursor) {

        if (limitStrong < 0 || limitMedium < 0) {
            return ResponseEntity.badRequest().build();
        }

        LocalDateTime since = parseSinceCursor(sinceCursor);
        if (sinceCursor != null && !sinceCursor.isBlank() && since == null) {
            return ResponseEntity.badRequest().build();
        }

        List<JobOffer> strong = fetchOffers(OfferScore.STRONG, limitStrong, onlyUnsent, since);
        List<JobOffer> medium = fetchOffers(OfferScore.MEDIUM, limitMedium, onlyUnsent, since);

        List<CareerDigestItemDto> items = new ArrayList<>();
        strong.forEach(offer -> items.add(toDigestItem(offer)));
        medium.forEach(offer -> items.add(toDigestItem(offer)));

        String nextCursor = items.stream()
                .map(CareerDigestItemDto::foundAt)
                .filter(java.util.Objects::nonNull)
                .max(Comparator.naturalOrder())
                .map(LocalDateTime::toString)
                .orElse(since != null ? since.toString() : null);

        String status = items.isEmpty() ? "EMPTY" : "READY";
        long unsentStrongCount = jobOfferRepository.countBySentAtIsNullAndScore(OfferScore.STRONG);
        long unsentMediumCount = jobOfferRepository.countBySentAtIsNullAndScore(OfferScore.MEDIUM);

        CareerDigestDto response = new CareerDigestDto(
                CONTRACT_VERSION,
                LocalDateTime.now(),
                status,
                nextCursor,
                items.size(),
                unsentStrongCount,
                unsentMediumCount,
                estimateTokens(items),
                items);

        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Get digest readiness status")
    @GetMapping("/status")
    public ResponseEntity<CareerDigestStatusDto> getDigestStatus() {
        ScrapeRun lastRun = scrapeRunRepository.findFirstByOrderByStartedAtDesc().orElse(null);
        ScoringTelemetry lastScoringTelemetry = scoringTelemetryRepository.findFirstByOrderByCreatedAtDesc()
                .orElse(null);

        long pendingScoreCount = jobOfferRepository.countByScore(OfferScore.PENDING_SCORE);
        long unsentStrongCount = jobOfferRepository.countBySentAtIsNullAndScore(OfferScore.STRONG);
        long unsentMediumCount = jobOfferRepository.countBySentAtIsNullAndScore(OfferScore.MEDIUM);

        String state = lastRun != null ? safe(lastRun.getStatus()) : "NO_RUNS";
        String status = pendingScoreCount > 0 ? "DEGRADED" : "READY";

        CareerDigestStatusDto response = new CareerDigestStatusDto(
                status,
                LocalDateTime.now(),
                lastRun != null ? lastRun.getStartedAt() : null,
                lastRun != null ? lastRun.getFinishedAt() : null,
                state,
                lastRun != null && lastRun.getNewOffersCount() != null ? lastRun.getNewOffersCount() : 0,
                pendingScoreCount,
                unsentStrongCount,
                unsentMediumCount,
                lastScoringTelemetry != null ? lastScoringTelemetry.getCreatedAt() : null,
                lastScoringTelemetry != null ? lastScoringTelemetry.getEstimatedInputTokens() : 0,
                lastScoringTelemetry != null ? lastScoringTelemetry.getEstimatedOutputTokens() : 0,
                lastScoringTelemetry != null ? safe(lastScoringTelemetry.getModelUsed()) : "N/A",
                lastScoringTelemetry != null ? safe(lastScoringTelemetry.getScoreSource()) : "NONE",
                lastScoringTelemetry != null ? safe(lastScoringTelemetry.getStatus()) : "NO_DATA");

        return ResponseEntity.ok(response);
    }

    @Operation(summary = "Acknowledge delivered offers")
    @PostMapping("/ack")
    @Transactional
    public ResponseEntity<CareerDigestAckResponseDto> acknowledgeDigest(
            @RequestBody CareerDigestAckRequestDto request) {

        if (request == null || request.offerIds() == null || request.offerIds().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }

        List<JobOffer> offers = jobOfferRepository.findAllById(request.offerIds());
        LocalDateTime acknowledgedAt = LocalDateTime.now();
        offers.forEach(offer -> offer.setSentAt(acknowledgedAt));
        jobOfferRepository.saveAll(offers);

        return ResponseEntity.ok(new CareerDigestAckResponseDto(offers.size(), acknowledgedAt));
    }

    private List<JobOffer> fetchOffers(OfferScore score, int limit, boolean onlyUnsent, LocalDateTime since) {
        if (limit == 0) {
            return List.of();
        }

        Pageable pageable = PageRequest.of(0, limit);

        if (onlyUnsent) {
            if (since != null) {
                return jobOfferRepository.findBySentAtIsNullAndScoreAndFoundAtAfterOrderByFoundAtDesc(score, since,
                        pageable);
            }
            return jobOfferRepository.findBySentAtIsNullAndScoreOrderByFoundAtDesc(score, pageable);
        }

        if (since != null) {
            return jobOfferRepository.findByScoreAndFoundAtAfterOrderByFoundAtDesc(score, since, pageable);
        }
        return jobOfferRepository.findByScoreOrderByFoundAtDesc(score, pageable);
    }

    private CareerDigestItemDto toDigestItem(JobOffer offer) {
        String score = offer.getScore() != null ? offer.getScore().name() : null;
        String shortReason = shorten(offer.getScoreReason(), REASON_MAX_CHARS);

        return new CareerDigestItemDto(
                offer.getId(),
                offer.getExternalId(),
                offer.getTitle(),
                offer.getCompany(),
                offer.getLocation(),
                score,
                shortReason,
                offer.getUrl(),
                offer.getFoundAt());
    }

    private long estimateTokens(List<CareerDigestItemDto> items) {
        int charCount = items.stream()
                .map(item -> String.join(" ",
                        safe(item.title()),
                        safe(item.company()),
                        safe(item.location()),
                        safe(item.score()),
                        safe(item.shortReason()),
                        safe(item.url())))
                .mapToInt(String::length)
                .sum();

        return (charCount / 4L) + 40L;
    }

    private String safe(String value) {
        return value != null ? value : "";
    }

    private String shorten(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars - 1) + "...";
    }

    private LocalDateTime parseSinceCursor(String sinceCursor) {
        if (sinceCursor == null || sinceCursor.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(sinceCursor);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
