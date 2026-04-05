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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
@RequiredArgsConstructor
@Slf4j
public class JobIngestionService {

    private final List<JobScraper> scrapers;
    private final JobOfferRepository jobOfferRepository;
    private final ScrapeRunRepository scrapeRunRepository;

    private long delayBetweenSourcesMs = 0;
    private long delayJitterMs = 0;

    @Value("${app.skills.career.scraping.delay-between-sources-ms:900}")
    void setDelayBetweenSourcesMs(long delayBetweenSourcesMs) {
        this.delayBetweenSourcesMs = Math.max(0, delayBetweenSourcesMs);
    }

    @Value("${app.skills.career.scraping.delay-jitter-ms:250}")
    void setDelayJitterMs(long delayJitterMs) {
        this.delayJitterMs = Math.max(0, delayJitterMs);
    }

    @Transactional
    public ScrapeRun ingest() {
        ScrapeRun run = ScrapeRun.builder()
                .startedAt(LocalDateTime.now())
                .build();

        int newOffers = 0;
        boolean hasError = false;

        for (int i = 0; i < scrapers.size(); i++) {
            pauseBetweenSourcesIfConfigured(i);
            JobScraper scraper = scrapers.get(i);
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

    private void pauseBetweenSourcesIfConfigured(int sourceIndex) {
        if (sourceIndex == 0 || delayBetweenSourcesMs <= 0) {
            return;
        }

        long jitter = delayJitterMs > 0 ? ThreadLocalRandom.current().nextLong(delayJitterMs + 1) : 0;
        long sleepMs = delayBetweenSourcesMs + jitter;

        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Scraper pacing sleep interrupted");
        }
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash)
                sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
