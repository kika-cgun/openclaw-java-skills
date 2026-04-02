package com.piotrcapecki.openclaw.skill.career.service;

import com.piotrcapecki.openclaw.skill.career.domain.JobOffer;
import com.piotrcapecki.openclaw.skill.career.domain.OfferScore;
import com.piotrcapecki.openclaw.skill.career.domain.ScrapeRun;
import com.piotrcapecki.openclaw.skill.career.repository.JobOfferRepository;
import com.piotrcapecki.openclaw.skill.career.repository.ScrapeRunRepository;
import com.piotrcapecki.openclaw.skill.career.scraper.JobScraper;
import com.piotrcapecki.openclaw.skill.career.scraper.RawJobOffer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class JobIngestionService {

    private final List<JobScraper> scrapers;
    private final JobOfferRepository jobOfferRepository;
    private final ScrapeRunRepository scrapeRunRepository;

    @Transactional
    public ScrapeRun ingest() {
        ScrapeRun run = ScrapeRun.builder()
                .startedAt(LocalDateTime.now())
                .build();

        int newOffers = 0;
        boolean hasError = false;

        for (JobScraper scraper : scrapers) {
            try {
                List<RawJobOffer> offers = scraper.scrape();
                for (RawJobOffer raw : offers) {
                    if (raw.url() == null) {
                        log.warn("Skipping offer with null URL from source {}: {}", raw.source(), raw.title());
                        continue;
                    }
                    String externalId = sha256(raw.url());
                    if (!jobOfferRepository.existsByExternalId(externalId)) {
                        jobOfferRepository.save(JobOffer.builder()
                                .externalId(externalId)
                                .source(raw.source())
                                .title(raw.title())
                                .company(raw.company())
                                .location(raw.location())
                                .url(raw.url())
                                .description(raw.description())
                                .score(OfferScore.PENDING_SCORE)
                                .foundAt(LocalDateTime.now())
                                .build());
                        newOffers++;
                    }
                }
            } catch (Exception e) {
                log.error("Scraper {} failed", scraper.getSource(), e);
                hasError = true;
            }
        }

        run.setStatus(hasError ? "PARTIAL" : "SUCCESS");
        run.setNewOffersCount(newOffers);
        run.setFinishedAt(LocalDateTime.now());
        return scrapeRunRepository.save(run);
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
