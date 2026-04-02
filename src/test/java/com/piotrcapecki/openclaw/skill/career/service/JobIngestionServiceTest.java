package com.piotrcapecki.openclaw.skill.career.service;

import com.piotrcapecki.openclaw.skill.career.domain.JobOffer;
import com.piotrcapecki.openclaw.skill.career.domain.JobSource;
import com.piotrcapecki.openclaw.skill.career.domain.ScrapeRun;
import com.piotrcapecki.openclaw.skill.career.repository.JobOfferRepository;
import com.piotrcapecki.openclaw.skill.career.repository.ScrapeRunRepository;
import com.piotrcapecki.openclaw.skill.career.scraper.JobScraper;
import com.piotrcapecki.openclaw.skill.career.scraper.RawJobOffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobIngestionServiceTest {

    @Mock JobOfferRepository jobOfferRepository;
    @Mock ScrapeRunRepository scrapeRunRepository;
    @Mock JobScraper scraperA;
    @Mock JobScraper scraperB;

    JobIngestionService service;

    @BeforeEach
    void setUp() {
        service = new JobIngestionService(
                List.of(scraperA, scraperB),
                jobOfferRepository,
                scrapeRunRepository
        );
    }

    @Test
    void savesNewOfferNotYetInDatabase() {
        RawJobOffer raw = new RawJobOffer(
                "Junior Java Dev", "Nordea", "Gdańsk",
                "https://example.com/job/1", "description", JobSource.JUSTJOINIT
        );
        when(scraperA.scrape()).thenReturn(List.of(raw));
        when(scraperB.scrape()).thenReturn(List.of());
        when(jobOfferRepository.existsByExternalId(anyString())).thenReturn(false);
        when(scrapeRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScrapeRun run = service.ingest();

        verify(jobOfferRepository, times(1)).save(any(JobOffer.class));
        assertThat(run.getNewOffersCount()).isEqualTo(1);
        assertThat(run.getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void skipsDuplicateOffer() {
        RawJobOffer raw = new RawJobOffer(
                "Junior Java Dev", "Nordea", "Gdańsk",
                "https://example.com/job/1", "description", JobSource.JUSTJOINIT
        );
        when(scraperA.scrape()).thenReturn(List.of(raw));
        when(scraperB.scrape()).thenReturn(List.of());
        when(jobOfferRepository.existsByExternalId(anyString())).thenReturn(true);
        when(scrapeRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScrapeRun run = service.ingest();

        verify(jobOfferRepository, never()).save(any(JobOffer.class));
        assertThat(run.getNewOffersCount()).isEqualTo(0);
    }

    @Test
    void continuesWhenOneScraperFails() {
        when(scraperA.scrape()).thenThrow(new RuntimeException("network error"));
        when(scraperA.getSource()).thenReturn(JobSource.AMAZON);
        when(scraperB.scrape()).thenReturn(List.of());
        when(scrapeRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScrapeRun run = service.ingest();

        assertThat(run.getStatus()).isEqualTo("PARTIAL");
        verify(scraperB).scrape();
        assertThat(run.getNewOffersCount()).isEqualTo(0);
    }
}
