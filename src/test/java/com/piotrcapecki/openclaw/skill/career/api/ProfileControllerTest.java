package com.piotrcapecki.openclaw.skill.career.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piotrcapecki.openclaw.skill.career.domain.UserProfile;
import com.piotrcapecki.openclaw.skill.career.repository.UserProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class ProfileControllerTest {

    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean UserProfileRepository userProfileRepository;

    MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void getProfileReturns200() throws Exception {
        UserProfile profile = UserProfile.builder().id(1L)
                .stack(List.of("Java")).level(List.of("junior"))
                .locations(List.of("Gdańsk")).preferences("Backend").build();
        when(userProfileRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(profile));

        mockMvc.perform(get("/api/career/profile").header("X-API-Key", "changeme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stack[0]").value("Java"));
    }

    @Test
    void getProfileReturns404WhenMissing() throws Exception {
        when(userProfileRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/career/profile").header("X-API-Key", "changeme"))
                .andExpect(status().isNotFound());
    }

    @Test
    void patchProfileCreatesOrUpdates() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "stack", List.of("Java", "Spring"),
                "level", List.of("junior"),
                "locations", List.of("Gdańsk"),
                "preferences", "Backend REST"
        ));
        when(userProfileRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());
        when(userProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(patch("/api/career/profile")
                        .header("X-API-Key", "changeme")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferences").value("Backend REST"));
    }
}
