package com.piotrcapecki.openclaw.skill.career.api;

import com.piotrcapecki.openclaw.skill.career.domain.JobOffer;
import com.piotrcapecki.openclaw.skill.career.domain.JobSource;
import com.piotrcapecki.openclaw.skill.career.domain.OfferScore;
import com.piotrcapecki.openclaw.skill.career.repository.JobOfferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class OffersControllerTest {

    @Autowired WebApplicationContext context;
    @MockitoBean JobOfferRepository jobOfferRepository;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void getOffersReturnsList() throws Exception {
        JobOffer offer = JobOffer.builder().id(UUID.randomUUID())
                .title("Junior Java Dev").company("Nordea").location("Gdańsk")
                .source(JobSource.JUSTJOINIT).score(OfferScore.STRONG).build();
        when(jobOfferRepository.findAllByOrderByFoundAtDesc()).thenReturn(List.of(offer));

        mockMvc.perform(get("/api/career/offers").header("X-API-Key", "changeme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].score").value("STRONG"));
    }

    @Test
    void getOfferByIdReturns200() throws Exception {
        UUID id = UUID.randomUUID();
        JobOffer offer = JobOffer.builder().id(id)
                .title("Junior Java Dev").company("Nordea").location("Gdańsk")
                .source(JobSource.JUSTJOINIT).score(OfferScore.STRONG).build();
        when(jobOfferRepository.findById(id)).thenReturn(Optional.of(offer));

        mockMvc.perform(get("/api/career/offers/" + id).header("X-API-Key", "changeme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Junior Java Dev"));
    }

    @Test
    void getOfferByIdReturns404WhenNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(jobOfferRepository.findById(id)).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/career/offers/" + id).header("X-API-Key", "changeme"))
                .andExpect(status().isNotFound());
    }
}
