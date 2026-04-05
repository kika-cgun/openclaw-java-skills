package com.piotrcapecki.openclaw.skill.career.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record CareerDigestItemDto(
        UUID id,
        String dedupKey,
        String title,
        String company,
        String location,
        String score,
        String shortReason,
        String url,
        LocalDateTime foundAt) {
}
