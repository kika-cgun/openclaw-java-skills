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

/**
 * Scrapes Pracuj.pl via their internal REST API used by the it.pracuj.pl frontend.
 *
 * Endpoint: https://it.pracuj.pl/api/v1/offers
 *
 * ⚠️ Requires browser-like headers — Cloudflare blocks plain server requests.
 *    Works correctly from residential/VPS IPs when run as a Spring Boot service.
 *
 * ⚠️ Verify field names against live API before first production run:
 *    curl "https://it.pracuj.pl/api/v1/offers?offset=0&limit=2&keyword=java" \
 *         -H "User-Agent: Mozilla/5.0 ..." -H "Accept: application/json" \
 *         -H "Referer: https://pracuj.pl/praca"
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PracujPlScraper implements JobScraper {

    private static final String BASE_URL = "https://it.pracuj.pl";
    private static final String API_URL  = BASE_URL +
            "/api/v1/offers" +
            "?offset=0&limit=50&sort=DATE_DESC" +
            "&keyword=java" +
            "&contractType=B2B,UOP" +
            "&experienceLevel=JUNIOR,TRAINEE";

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Override
    public JobSource getSource() { return JobSource.PRACUJ; }

    @Override
    public List<RawJobOffer> scrape() {
        Request request = new Request.Builder()
                .url(API_URL)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36")
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "pl-PL,pl;q=0.9,en;q=0.8")
                .header("Referer", "https://pracuj.pl/praca")
                .header("Origin", "https://it.pracuj.pl")
                .header("sec-fetch-dest", "empty")
                .header("sec-fetch-mode", "cors")
                .header("sec-fetch-site", "same-origin")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                log.warn("Pracuj.pl API returned {}", response.code());
                return List.of();
            }

            JsonNode root = objectMapper.readTree(body.string());
            List<RawJobOffer> result = new ArrayList<>();

            // ⚠️ Field path depends on actual API response shape.
            // Try "groupedOffers" first (Pracuj internal search API convention),
            // fall back to "offers" / root array.
            JsonNode offers = root.path("groupedOffers");
            if (!offers.isArray() || offers.isEmpty()) {
                offers = root.path("offers");
            }
            if (!offers.isArray() || offers.isEmpty()) {
                // some endpoints return a root-level array
                offers = root.isArray() ? root : root.path("data");
            }

            for (JsonNode offer : offers) {
                String title = offer.path("jobTitle").asText(
                        offer.path("title").asText(
                                offer.path("name").asText("")));

                String company = offer.path("companyName").asText(
                        offer.path("employer").path("name").asText("Unknown"));

                // location: try nested offers[0].displayWorkplace, then direct fields
                JsonNode firstOffer = offer.path("offers").path(0);
                String city = firstOffer.path("displayWorkplace").asText(
                        offer.path("location").path("city").asText(
                                offer.path("city").asText("Poland")));

                String url = firstOffer.path("offerAbsoluteUri").asText(
                        offer.path("offerAbsoluteUri").asText(
                                offer.path("url").asText(
                                        offer.path("absoluteUri").asText(""))));

                if (title.isBlank() || url.isBlank()) continue;

                result.add(new RawJobOffer(
                        title, company, city, url,
                        title + " at " + company + " — " + city,
                        JobSource.PRACUJ
                ));
            }
            return result;
        } catch (Exception e) {
            log.error("Pracuj.pl scraper error", e);
            throw new RuntimeException("Pracuj.pl scrape failed", e);
        }
    }
}
