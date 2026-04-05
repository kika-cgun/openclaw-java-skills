package com.piotrcapecki.openclaw.skill.career.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piotrcapecki.openclaw.skill.career.domain.JobOffer;
import com.piotrcapecki.openclaw.skill.career.domain.JobSource;
import com.piotrcapecki.openclaw.skill.career.domain.OfferScore;
import com.piotrcapecki.openclaw.skill.career.domain.ScoringTelemetry;
import com.piotrcapecki.openclaw.skill.career.domain.ScrapeRun;
import com.piotrcapecki.openclaw.skill.career.repository.JobOfferRepository;
import com.piotrcapecki.openclaw.skill.career.repository.ScoringTelemetryRepository;
import com.piotrcapecki.openclaw.skill.career.repository.ScrapeRunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class DigestControllerTest {

    @Autowired
    WebApplicationContext context;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    JobOfferRepository jobOfferRepository;

    @MockitoBean
    ScrapeRunRepository scrapeRunRepository;

    @MockitoBean
    ScoringTelemetryRepository scoringTelemetryRepository;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void getCompactReturnsReadyDigest() throws Exception {
        JobOffer strong = JobOffer.builder()
                .id(UUID.randomUUID())
                .externalId("ext-strong")
                .title("Junior Java Developer")
                .company("Acme")
                .location("Gdansk")
                .url("https://example.com/strong")
                .score(OfferScore.STRONG)
                .scoreReason("Bardzo dobre dopasowanie stacku i poziomu")
                .foundAt(LocalDateTime.now())
                .source(JobSource.JUSTJOINIT)
                .build();

        JobOffer medium = JobOffer.builder()
                .id(UUID.randomUUID())
                .externalId("ext-medium")
                .title("Java Internship")
                .company("Beta")
                .location("Remote")
                .url("https://example.com/medium")
                .score(OfferScore.MEDIUM)
                .scoreReason("Wymaga nauki jednego brakujacego frameworka")
                .foundAt(LocalDateTime.now().minusHours(1))
                .source(JobSource.NOFLUFFJOBS)
                .build();

        when(jobOfferRepository.findBySentAtIsNullAndScoreOrderByFoundAtDesc(eq(OfferScore.STRONG),
                any(Pageable.class)))
                .thenReturn(List.of(strong));
        when(jobOfferRepository.findBySentAtIsNullAndScoreOrderByFoundAtDesc(eq(OfferScore.MEDIUM),
                any(Pageable.class)))
                .thenReturn(List.of(medium));
        when(jobOfferRepository.countBySentAtIsNullAndScore(OfferScore.STRONG)).thenReturn(3L);
        when(jobOfferRepository.countBySentAtIsNullAndScore(OfferScore.MEDIUM)).thenReturn(8L);

        mockMvc.perform(get("/api/career/digest/compact")
                .header("X-API-Key", "changeme")
                .param("limitStrong", "1")
                .param("limitMedium", "1")
                .param("onlyUnsent", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contractVersion").value("career-digest.v1"))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.returnedCount").value(2))
                .andExpect(jsonPath("$.unsentStrongCount").value(3))
                .andExpect(jsonPath("$.items[0].score").value("STRONG"))
                .andExpect(jsonPath("$.items[1].score").value("MEDIUM"));
    }

    @Test
    void getCompactReturns400WhenCursorIsInvalid() throws Exception {
        mockMvc.perform(get("/api/career/digest/compact")
                .header("X-API-Key", "changeme")
                .param("sinceCursor", "not-a-date"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postAckMarksOffersAsSent() throws Exception {
        JobOffer offer = JobOffer.builder()
                .id(UUID.randomUUID())
                .externalId("ext-1")
                .score(OfferScore.STRONG)
                .build();

        when(jobOfferRepository.findAllById(List.of(offer.getId()))).thenReturn(List.of(offer));

        String body = objectMapper.writeValueAsString(Map.of("offerIds", List.of(offer.getId())));

        mockMvc.perform(post("/api/career/digest/ack")
                .header("X-API-Key", "changeme")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedCount").value(1));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<JobOffer>> captor = ArgumentCaptor.forClass(List.class);
        verify(jobOfferRepository).saveAll(captor.capture());
        assertThat(captor.getValue().getFirst().getSentAt()).isNotNull();
    }

    @Test
    void getStatusReturnsReadiness() throws Exception {
        ScrapeRun run = ScrapeRun.builder()
                .id(UUID.randomUUID())
                .startedAt(LocalDateTime.now().minusMinutes(10))
                .finishedAt(LocalDateTime.now().minusMinutes(8))
                .newOffersCount(7)
                .status("SUCCESS")
                .build();

        when(scrapeRunRepository.findFirstByOrderByStartedAtDesc()).thenReturn(Optional.of(run));
        when(scoringTelemetryRepository.findFirstByOrderByCreatedAtDesc())
                .thenReturn(Optional.of(ScoringTelemetry.builder()
                        .id(UUID.randomUUID())
                        .createdAt(LocalDateTime.now().minusMinutes(2))
                        .pendingOffers(3)
                        .selectedForModel(2)
                        .autoSkippedCount(1)
                        .cacheHitCount(1)
                        .llmScoredCount(1)
                        .estimatedInputTokens(321)
                        .estimatedOutputTokens(87)
                        .modelUsed("stepfun/step-3-5-flash:free")
                        .scoreSource("RULES+CACHE+LLM")
                        .status("SUCCESS")
                        .build()));
        when(jobOfferRepository.countByScore(OfferScore.PENDING_SCORE)).thenReturn(0L);
        when(jobOfferRepository.countBySentAtIsNullAndScore(OfferScore.STRONG)).thenReturn(2L);
        when(jobOfferRepository.countBySentAtIsNullAndScore(OfferScore.MEDIUM)).thenReturn(5L);

        mockMvc.perform(get("/api/career/digest/status")
                .header("X-API-Key", "changeme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.lastRunState").value("SUCCESS"))
                .andExpect(jsonPath("$.lastRunNewOffers").value(7))
                .andExpect(jsonPath("$.unsentStrongCount").value(2))
                .andExpect(jsonPath("$.lastEstimatedInputTokens").value(321))
                .andExpect(jsonPath("$.lastModelUsed").value("stepfun/step-3-5-flash:free"))
                .andExpect(jsonPath("$.lastScoreSource").value("RULES+CACHE+LLM"));
    }
}
