package com.piotrcapecki.openclaw.skill.career.scraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piotrcapecki.openclaw.skill.career.domain.JobSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class NoFluffJobsScraper implements JobScraper {

    private static final String API_URL = "https://nofluffjobs.com/api/v2/postings";
    private static final Set<String> ACCEPTED_CITIES =
            Set.of("gdańsk", "gdynia", "sopot", "trójmiasto");
    private static final Set<String> ACCEPTED_SENIORITY =
            Set.of("junior", "intern", "trainee");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Override
    public JobSource getSource() { return JobSource.NOFLUFFJOBS; }

    @Override
    public List<RawJobOffer> scrape() {
        try {
            String requestBodyJson = objectMapper.writeValueAsString(Map.of(
                    "criteriaSearch", Map.of(
                            "category", List.of("backend"),
                            "technology", List.of("java")
                    )
            ));

            Request request = new Request.Builder()
                    .url(API_URL)
                    .post(RequestBody.create(requestBodyJson, MediaType.parse("application/json")))
                    .header("Accept", "application/json")
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                ResponseBody body = response.body();
                if (!response.isSuccessful() || body == null) {
                    log.warn("NoFluffJobs API returned {}", response.code());
                    return List.of();
                }

                JsonNode root = objectMapper.readTree(body.string());
                List<RawJobOffer> result = new ArrayList<>();

                for (JsonNode posting : root.path("postings")) {
                    if (!isJuniorOrIntern(posting)) continue;
                    if (!isTriCityOrRemote(posting)) continue;

                    String id = posting.path("id").asText();
                    String title = posting.path("name").asText();
                    String company = posting.path("company").path("name").asText();
                    String location = extractLocation(posting);

                    result.add(new RawJobOffer(
                            title, company, location,
                            "https://nofluffjobs.com/pl/job/" + id,
                            title + " at " + company,
                            JobSource.NOFLUFFJOBS
                    ));
                }
                return result;
            }
        } catch (Exception e) {
            log.error("NoFluffJobs scraper error", e);
            throw new RuntimeException("NoFluffJobs scrape failed", e);
        }
    }

    private boolean isJuniorOrIntern(JsonNode posting) {
        for (JsonNode s : posting.path("seniority"))
            if (ACCEPTED_SENIORITY.contains(s.asText().toLowerCase())) return true;
        return false;
    }

    private boolean isTriCityOrRemote(JsonNode posting) {
        for (JsonNode place : posting.path("location").path("places"))
            if (ACCEPTED_CITIES.contains(place.path("city").asText("").toLowerCase())) return true;
        return posting.path("remote").asBoolean(false);
    }

    private String extractLocation(JsonNode posting) {
        JsonNode places = posting.path("location").path("places");
        if (!places.isEmpty()) {
            String city = places.get(0).path("city").asText("");
            if (!city.isBlank()) return city;
        }
        return posting.path("remote").asBoolean(false) ? "remote" : "N/A";
    }
}
