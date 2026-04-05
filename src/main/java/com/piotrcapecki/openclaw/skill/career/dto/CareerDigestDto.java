package com.piotrcapecki.openclaw.skill.career.dto;

import java.time.LocalDateTime;
import java.util.List;

public record CareerDigestDto(
        String contractVersion,
        LocalDateTime generatedAt,
        String status,
        String nextCursor,
        int returnedCount,
        long unsentStrongCount,
        long unsentMediumCount,
        long estimatedTokens,
        List<CareerDigestItemDto> items) {
}
