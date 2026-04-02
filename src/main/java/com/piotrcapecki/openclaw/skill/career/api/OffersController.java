package com.piotrcapecki.openclaw.skill.career.api;

import com.piotrcapecki.openclaw.skill.career.dto.JobOfferDto;
import com.piotrcapecki.openclaw.skill.career.repository.JobOfferRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/career/offers")
@RequiredArgsConstructor
public class OffersController {

    private final JobOfferRepository jobOfferRepository;

    @GetMapping
    public ResponseEntity<List<JobOfferDto>> getOffers() {
        return ResponseEntity.ok(jobOfferRepository.findAllByOrderByFoundAtDesc()
                .stream().map(JobOfferDto::from).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<JobOfferDto> getOffer(@PathVariable UUID id) {
        return jobOfferRepository.findById(id)
                .map(JobOfferDto::from).map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
