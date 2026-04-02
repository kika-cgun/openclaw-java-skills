package com.piotrcapecki.openclaw.skill.career.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piotrcapecki.openclaw.core.ai.OpenRouterClient;
import com.piotrcapecki.openclaw.skill.career.domain.JobOffer;
import com.piotrcapecki.openclaw.skill.career.domain.OfferScore;
import com.piotrcapecki.openclaw.skill.career.domain.UserProfile;
import com.piotrcapecki.openclaw.skill.career.repository.JobOfferRepository;
import com.piotrcapecki.openclaw.skill.career.repository.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CareerScoringServiceTest {

    @Mock OpenRouterClient openRouterClient;
    @Mock JobOfferRepository jobOfferRepository;
    @Mock UserProfileRepository userProfileRepository;
    @Mock ObjectMapper objectMapper;
    @InjectMocks CareerScoringService service;

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
        when(objectMapper.writeValueAsString(anyList())).thenReturn("[]");
        when(openRouterClient.complete(anyString())).thenReturn(claudeResponse);

        service.scoreAllPending();

        ArgumentCaptor<JobOffer> captor = ArgumentCaptor.forClass(JobOffer.class);
        verify(jobOfferRepository).save(captor.capture());
        assertThat(captor.getValue().getScore()).isEqualTo(OfferScore.STRONG);
        assertThat(captor.getValue().getScoreReason()).isEqualTo("Idealne dopasowanie");
    }

    @Test
    void doesNothingWhenNoPendingOffers() {
        when(jobOfferRepository.findByScore(OfferScore.PENDING_SCORE)).thenReturn(List.of());
        service.scoreAllPending();
        verify(openRouterClient, never()).complete(anyString());
    }
}
