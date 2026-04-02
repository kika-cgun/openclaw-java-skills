package com.piotrcapecki.openclaw.skill.career.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "job_offers")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class JobOffer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    private JobSource source;

    @Column(unique = true)
    private String externalId;

    private String title;
    private String company;
    private String location;

    @Column(columnDefinition = "TEXT")
    private String url;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private OfferScore score = OfferScore.PENDING_SCORE;

    @Column(columnDefinition = "TEXT")
    private String scoreReason;

    private LocalDateTime foundAt;
    private LocalDateTime sentAt;
}
