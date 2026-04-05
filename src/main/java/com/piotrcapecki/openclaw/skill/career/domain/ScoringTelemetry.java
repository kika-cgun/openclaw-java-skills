package com.piotrcapecki.openclaw.skill.career.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "career_scoring_telemetry")
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ScoringTelemetry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @EqualsAndHashCode.Include
    private UUID id;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "pending_offers", nullable = false)
    private int pendingOffers;

    @Column(name = "selected_for_model", nullable = false)
    private int selectedForModel;

    @Column(name = "auto_skipped_count", nullable = false)
    private int autoSkippedCount;

    @Column(name = "cache_hit_count", nullable = false)
    private int cacheHitCount;

    @Column(name = "llm_scored_count", nullable = false)
    private int llmScoredCount;

    @Column(name = "estimated_input_tokens", nullable = false)
    private long estimatedInputTokens;

    @Column(name = "estimated_output_tokens", nullable = false)
    private long estimatedOutputTokens;

    @Column(name = "model_used", length = 128, nullable = false)
    private String modelUsed;

    @Column(name = "score_source", length = 64, nullable = false)
    private String scoreSource;

    @Column(name = "status", length = 24, nullable = false)
    private String status;
}
