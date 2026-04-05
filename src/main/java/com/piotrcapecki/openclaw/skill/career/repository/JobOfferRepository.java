package com.piotrcapecki.openclaw.skill.career.repository;

import com.piotrcapecki.openclaw.skill.career.domain.JobOffer;
import com.piotrcapecki.openclaw.skill.career.domain.OfferScore;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface JobOfferRepository extends JpaRepository<JobOffer, UUID> {
    boolean existsByExternalId(String externalId);

    List<JobOffer> findByScore(OfferScore score);

    List<JobOffer> findBySentAtIsNullAndScore(OfferScore score);

    List<JobOffer> findAllByOrderByFoundAtDesc();

    long countBySentAtIsNullAndScore(OfferScore score);

    List<JobOffer> findBySentAtIsNullAndScoreOrderByFoundAtDesc(OfferScore score, Pageable pageable);

    List<JobOffer> findByScoreOrderByFoundAtDesc(OfferScore score, Pageable pageable);

    List<JobOffer> findBySentAtIsNullAndScoreAndFoundAtAfterOrderByFoundAtDesc(
            OfferScore score, LocalDateTime foundAt, Pageable pageable);

    List<JobOffer> findByScoreAndFoundAtAfterOrderByFoundAtDesc(
            OfferScore score, LocalDateTime foundAt, Pageable pageable);

    long countByScore(OfferScore score);
}
