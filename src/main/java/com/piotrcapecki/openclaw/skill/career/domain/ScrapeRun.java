package com.piotrcapecki.openclaw.skill.career.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "scrape_runs")
@Getter @Setter @ToString
@NoArgsConstructor @AllArgsConstructor @Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ScrapeRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Integer newOffersCount;
    private String status;
}
