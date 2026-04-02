package com.piotrcapecki.openclaw.skill.career.repository;

import com.piotrcapecki.openclaw.skill.career.domain.ScrapeRun;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ScrapeRunRepository extends JpaRepository<ScrapeRun, UUID> {
    List<ScrapeRun> findAllByOrderByStartedAtDesc();
}
