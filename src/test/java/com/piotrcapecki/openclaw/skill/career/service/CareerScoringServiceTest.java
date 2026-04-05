package com.piotrcapecki.openclaw.skill.career.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piotrcapecki.openclaw.core.ai.OpenRouterClient;
import com.piotrcapecki.openclaw.skill.career.domain.JobOffer;
import com.piotrcapecki.openclaw.skill.career.domain.OfferScore;
import com.piotrcapecki.openclaw.skill.career.domain.ScoringDecisionCache;
import com.piotrcapecki.openclaw.skill.career.domain.ScoringTelemetry;
import com.piotrcapecki.openclaw.skill.career.domain.UserProfile;
import com.piotrcapecki.openclaw.skill.career.repository.JobOfferRepository;
import com.piotrcapecki.openclaw.skill.career.repository.ScoringDecisionCacheRepository;
import com.piotrcapecki.openclaw.skill.career.repository.ScoringTelemetryRepository;
import com.piotrcapecki.openclaw.skill.career.repository.UserProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CareerScoringServiceTest {

    @Mock
    OpenRouterClient openRouterClient;
    @Mock
    JobOfferRepository jobOfferRepository;
    @Mock
    UserProfileRepository userProfileRepository;
    @Mock
    ScoringDecisionCacheRepository scoringDecisionCacheRepository;
    @Mock
    ScoringTelemetryRepository scoringTelemetryRepository;

    CareerScoringService service;

    @BeforeEach
    void setUp() {
        service = new CareerScoringService(
                openRouterClient,
                jobOfferRepository,
                userProfileRepository,
                scoringDecisionCacheRepository,
                scoringTelemetryRepository,
                new ObjectMapper());
    }

    @Test
    void updatesOfferScoreFromOpenRouterResponse() throws Exception {
        UUID offerId = UUID.randomUUID();
        JobOffer offer = JobOffer.builder()
                .id(offerId).title("Junior Java Dev").company("Nordea")
                .location("Gdańsk").url("https://example.com").description("Java role")
                .score(OfferScore.PENDING_SCORE).build();

        UserProfile profile = UserProfile.builder()
                .stack(List.of("Java", "Spring Boot"))
                .level(List.of("junior"))
                .locations(List.of("Gdańsk", "remote"))
                .preferences("Backend REST APIs")
                .build();

        String claudeResponse = """
                [{"offerId":"%s","score":"STRONG","reason":"Idealne dopasowanie"}]
                """.formatted(offerId);

        when(jobOfferRepository.findByScore(OfferScore.PENDING_SCORE)).thenReturn(List.of(offer));
        when(userProfileRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(profile));
        when(scoringDecisionCacheRepository.findByCacheKeyIn(argThat((Collection<String> c) -> true)))
                .thenReturn(List.of());
        when(scoringDecisionCacheRepository.findByCacheKey(anyString())).thenReturn(Optional.empty());
        when(openRouterClient.complete(anyString())).thenReturn(claudeResponse);

        service.scoreAllPending();

        ArgumentCaptor<JobOffer> captor = ArgumentCaptor.forClass(JobOffer.class);
        verify(jobOfferRepository).save(captor.capture());
        assertThat(captor.getValue().getScore()).isEqualTo(OfferScore.STRONG);
        assertThat(captor.getValue().getScoreReason()).isEqualTo("Idealne dopasowanie");
        verify(scoringDecisionCacheRepository).save(any(ScoringDecisionCache.class));
        verify(scoringTelemetryRepository).save(any(ScoringTelemetry.class));
    }

    @Test
    void doesNothingWhenNoPendingOffers() {
        when(jobOfferRepository.findByScore(OfferScore.PENDING_SCORE)).thenReturn(List.of());
        service.scoreAllPending();
        verify(openRouterClient, never()).complete(anyString());
        verify(userProfileRepository, never()).findFirstByOrderByIdAsc();
        verify(scoringTelemetryRepository).save(any(ScoringTelemetry.class));
    }

    @Test
    void rulePrefilterMarksIrrelevantOffersAsSkipWithoutCallingLlm() {
        JobOffer offer = JobOffer.builder()
                .id(UUID.randomUUID())
                .title("Senior Python Engineer")
                .company("Legacy Inc")
                .location("London")
                .description("Python, Django, 8 years exp")
                .score(OfferScore.PENDING_SCORE)
                .build();

        UserProfile profile = UserProfile.builder()
                .stack(List.of("Java", "Spring"))
                .level(List.of("junior"))
                .locations(List.of("Gdansk", "remote"))
                .preferences("Backend")
                .build();

        when(jobOfferRepository.findByScore(OfferScore.PENDING_SCORE)).thenReturn(List.of(offer));
        when(userProfileRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(profile));

        service.scoreAllPending();

        verify(openRouterClient, never()).complete(anyString());
        verify(jobOfferRepository).saveAll(any());
        assertThat(offer.getScore()).isEqualTo(OfferScore.SKIP);
        assertThat(offer.getScoreReason()).contains("Automatyczny filtr");
    }

    @Test
    void respectsMaxOffersPerRunLimit() {
        UUID newerId = UUID.randomUUID();
        UUID olderId = UUID.randomUUID();

        JobOffer newer = JobOffer.builder()
                .id(newerId)
                .title("Junior Java Dev")
                .company("Nordea")
                .location("Gdansk")
                .description("Java Spring backend")
                .foundAt(LocalDateTime.now())
                .score(OfferScore.PENDING_SCORE)
                .build();

        JobOffer older = JobOffer.builder()
                .id(olderId)
                .title("Junior Java Engineer")
                .company("Acme")
                .location("Gdansk")
                .description("Java Spring data")
                .foundAt(LocalDateTime.now().minusDays(1))
                .score(OfferScore.PENDING_SCORE)
                .build();

        UserProfile profile = UserProfile.builder()
                .stack(List.of("Java"))
                .level(List.of("junior"))
                .locations(List.of("Gdansk"))
                .preferences("Backend")
                .build();

        service.setMaxOffersPerRun(1);

        String response = "[{\"offerId\":\"" + newerId + "\",\"score\":\"STRONG\",\"reason\":\"ok\"}]";

        when(jobOfferRepository.findByScore(OfferScore.PENDING_SCORE)).thenReturn(List.of(older, newer));
        when(userProfileRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(profile));
        when(scoringDecisionCacheRepository.findByCacheKeyIn(argThat((Collection<String> c) -> true)))
                .thenReturn(List.of());
        when(scoringDecisionCacheRepository.findByCacheKey(anyString())).thenReturn(Optional.empty());
        when(openRouterClient.complete(anyString())).thenReturn(response);

        service.scoreAllPending();

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(openRouterClient).complete(promptCaptor.capture());
        assertThat(promptCaptor.getValue()).contains(newerId.toString());
        assertThat(promptCaptor.getValue()).doesNotContain(olderId.toString());
    }

    @Test
    void usesCachedDecisionWithoutCallingLlm() {
        JobOffer offer = JobOffer.builder()
                .id(UUID.randomUUID())
                .title("Junior Java Dev")
                .company("Acme")
                .location("Gdansk")
                .description("Java Spring")
                .score(OfferScore.PENDING_SCORE)
                .build();

        UserProfile profile = UserProfile.builder()
                .stack(List.of("Java"))
                .level(List.of("junior"))
                .locations(List.of("Gdansk"))
                .preferences("Backend")
                .build();

        when(jobOfferRepository.findByScore(OfferScore.PENDING_SCORE)).thenReturn(List.of(offer));
        when(userProfileRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(profile));
        when(scoringDecisionCacheRepository.findByCacheKeyIn(argThat((Collection<String> c) -> true)))
                .thenAnswer(invocation -> {
                    List<String> keys = invocation.getArgument(0);
                    return List.of(ScoringDecisionCache.builder()
                            .cacheKey(keys.getFirst())
                            .offerId(offer.getId())
                            .profileHash("ph")
                            .modelVersion("model")
                            .score(OfferScore.MEDIUM)
                            .reason("Cached decision")
                            .createdAt(LocalDateTime.now().minusDays(1))
                            .updatedAt(LocalDateTime.now())
                            .build());
                });

        service.scoreAllPending();

        verify(openRouterClient, never()).complete(anyString());
        verify(jobOfferRepository).saveAll(argThat((Iterable<JobOffer> offers) -> true));
        assertThat(offer.getScore()).isEqualTo(OfferScore.MEDIUM);
        assertThat(offer.getScoreReason()).isEqualTo("Cached decision");
    }
}
