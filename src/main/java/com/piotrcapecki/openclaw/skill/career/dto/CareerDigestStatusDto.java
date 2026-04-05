package com.piotrcapecki.openclaw.skill.career.dto;

import java.time.LocalDateTime;

public record CareerDigestStatusDto(
        String status,
        LocalDateTime generatedAt,
        LocalDateTime lastRunStartedAt,
        LocalDateTime lastRunFinishedAt,
        String lastRunState,
        int lastRunNewOffers,
        long pendingScoreCount,
        long unsentStrongCount,
        long unsentMediumCount) {
}
