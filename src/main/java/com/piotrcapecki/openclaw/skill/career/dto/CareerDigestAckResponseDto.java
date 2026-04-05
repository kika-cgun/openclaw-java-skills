package com.piotrcapecki.openclaw.skill.career.dto;

import java.time.LocalDateTime;

public record CareerDigestAckResponseDto(
        int updatedCount,
        LocalDateTime acknowledgedAt) {
}
