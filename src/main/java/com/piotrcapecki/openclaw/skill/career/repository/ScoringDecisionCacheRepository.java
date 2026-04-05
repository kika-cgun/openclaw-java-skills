package com.piotrcapecki.openclaw.skill.career.repository;

import com.piotrcapecki.openclaw.skill.career.domain.ScoringDecisionCache;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScoringDecisionCacheRepository extends JpaRepository<ScoringDecisionCache, UUID> {
    List<ScoringDecisionCache> findByCacheKeyIn(Collection<String> cacheKeys);

    Optional<ScoringDecisionCache> findByCacheKey(String cacheKey);
}
