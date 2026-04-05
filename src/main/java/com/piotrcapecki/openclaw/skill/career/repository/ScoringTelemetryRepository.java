package com.piotrcapecki.openclaw.skill.career.repository;

import com.piotrcapecki.openclaw.skill.career.domain.ScoringTelemetry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ScoringTelemetryRepository extends JpaRepository<ScoringTelemetry, UUID> {
    Optional<ScoringTelemetry> findFirstByOrderByCreatedAtDesc();
}
