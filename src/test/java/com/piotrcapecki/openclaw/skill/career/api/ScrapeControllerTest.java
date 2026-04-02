package com.piotrcapecki.openclaw.skill.career.api;

import com.piotrcapecki.openclaw.skill.career.domain.ScrapeRun;
import com.piotrcapecki.openclaw.skill.career.repository.ScrapeRunRepository;
import com.piotrcapecki.openclaw.skill.career.scheduler.CareerScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class ScrapeControllerTest {

    @Autowired WebApplicationContext context;
    @MockitoBean CareerScheduler careerScheduler;
    @MockitoBean ScrapeRunRepository scrapeRunRepository;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void postRunTriggersPipeline() throws Exception {
        mockMvc.perform(post("/api/career/scrape/run").header("X-API-Key", "changeme"))
                .andExpect(status().isOk());
        verify(careerScheduler, times(1)).runDailyPipeline();
    }

    @Test
    void getRunsReturnsHistory() throws Exception {
        ScrapeRun run = ScrapeRun.builder().id(UUID.randomUUID())
                .startedAt(LocalDateTime.now()).finishedAt(LocalDateTime.now())
                .newOffersCount(5).status("SUCCESS").build();
        when(scrapeRunRepository.findAllByOrderByStartedAtDesc()).thenReturn(List.of(run));

        mockMvc.perform(get("/api/career/scrape/runs").header("X-API-Key", "changeme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$[0].newOffersCount").value(5));
    }
}
