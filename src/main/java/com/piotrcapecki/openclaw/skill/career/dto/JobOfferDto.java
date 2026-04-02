package com.piotrcapecki.openclaw.skill.career.dto;

import com.piotrcapecki.openclaw.skill.career.domain.JobOffer;

import java.time.LocalDateTime;
import java.util.UUID;

public record JobOfferDto(UUID id, String source, String title, String company,
                           String location, String url, String score, String scoreReason,
                           LocalDateTime foundAt, LocalDateTime sentAt) {
    public static JobOfferDto from(JobOffer o) {
        return new JobOfferDto(o.getId(),
                o.getSource() != null ? o.getSource().name() : null,
                o.getTitle(), o.getCompany(), o.getLocation(), o.getUrl(),
                o.getScore() != null ? o.getScore().name() : null,
                o.getScoreReason(), o.getFoundAt(), o.getSentAt());
    }
}
