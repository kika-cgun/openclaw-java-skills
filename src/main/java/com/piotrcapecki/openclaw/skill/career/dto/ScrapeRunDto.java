package com.piotrcapecki.openclaw.skill.career.dto;

import com.piotrcapecki.openclaw.skill.career.domain.ScrapeRun;

import java.time.LocalDateTime;
import java.util.UUID;

public record ScrapeRunDto(
        UUID id, LocalDateTime startedAt, LocalDateTime finishedAt,
        Integer newOffersCount, String status
) {
    public static ScrapeRunDto from(ScrapeRun run) {
        return new ScrapeRunDto(run.getId(), run.getStartedAt(), run.getFinishedAt(),
                run.getNewOffersCount(), run.getStatus());
    }
}
