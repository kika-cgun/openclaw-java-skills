package com.piotrcapecki.openclaw.skill.career.scraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piotrcapecki.openclaw.skill.career.domain.JobSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class JustJoinItScraper implements JobScraper {

    private static final String API_URL = "https://justjoin.it/api/offers";
    private static final Set<String> ACCEPTED_CITIES = Set.of("gdańsk", "gdynia", "sopot");
    private static final Set<String> ACCEPTED_LEVELS = Set.of("junior", "intern", "internship");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Override
    public JobSource getSource() { return JobSource.JUSTJOINIT; }

    @Override
    public List<RawJobOffer> scrape() {
        Request request = new Request.Builder()
                .url(API_URL).header("Accept", "application/json").build();

        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                log.warn("JustJoinIT API returned {}", response.code());
                return List.of();
            }
            JsonNode offers = objectMapper.readTree(body.string());
            List<RawJobOffer> result = new ArrayList<>();

            for (JsonNode offer : offers) {
                if (!isJava(offer) || !isJuniorOrIntern(offer)) continue;
                if (!isTriCity(offer) && !isRemote(offer)) continue;

                String slug = offer.path("slug").asText();
                result.add(new RawJobOffer(
                        offer.path("title").asText(),
                        offer.path("company_name").asText(),
                        offer.path("city").asText(),
                        "https://justjoin.it/offers/" + slug,
                        offer.path("title").asText() + " at " + offer.path("company_name").asText(),
                        JobSource.JUSTJOINIT
                ));
            }
            return result;
        } catch (Exception e) {
            log.error("JustJoinIT scraper error", e);
            throw new RuntimeException("JustJoinIT scrape failed", e);
        }
    }

    private boolean isJava(JsonNode o) {
        return o.path("marker_icon").asText().toLowerCase().contains("java")
                || o.path("title").asText().toLowerCase().contains("java");
    }

    private boolean isJuniorOrIntern(JsonNode o) {
        return ACCEPTED_LEVELS.contains(o.path("experience_level").asText().toLowerCase());
    }

    private boolean isTriCity(JsonNode o) {
        return ACCEPTED_CITIES.contains(o.path("city").asText().toLowerCase());
    }

    private boolean isRemote(JsonNode o) {
        return "remote".equals(o.path("workplace_type").asText().toLowerCase());
    }
}
