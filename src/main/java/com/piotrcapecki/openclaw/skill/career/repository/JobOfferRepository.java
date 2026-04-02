package com.piotrcapecki.openclaw.skill.career.repository;

import com.piotrcapecki.openclaw.skill.career.domain.JobOffer;
import com.piotrcapecki.openclaw.skill.career.domain.OfferScore;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface JobOfferRepository extends JpaRepository<JobOffer, UUID> {
    boolean existsByExternalId(String externalId);
    List<JobOffer> findByScore(OfferScore score);
    List<JobOffer> findBySentAtIsNullAndScore(OfferScore score);
    List<JobOffer> findAllByOrderByFoundAtDesc();
}
