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

/**
 * Scrapes Bulldogjob.pl via their internal JSON feed used by their own frontend.
 *
 * Endpoint: https://bulldogjob.pl/companies/jobs/s/skills,Java/order,date
 *           Accepts: application/json → returns structured job data
 *
 * ⚠️ Requires browser-like headers — Cloudflare blocks plain server requests.
 *    Works correctly from residential/VPS IPs when run as a Spring Boot service.
 *
 * ⚠️ Verify field names against live API before first production run:
 *    curl "https://bulldogjob.pl/companies/jobs/s/skills,Java/order,date" \
 *         -H "Accept: application/json" -H "User-Agent: Mozilla/5.0 ..."
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BulldogjobScraper implements JobScraper {

    private static final String BASE_URL = "https://bulldogjob.pl";
    private static final String API_URL  = BASE_URL +
            "/companies/jobs/s/skills,Java/order,date";

    private static final Set<String> ACCEPTED_CITIES =
            Set.of("gdańsk", "gdynia", "sopot", "trójmiasto", "remote");

    private static final Set<String> ACCEPTED_SENIORITY =
            Set.of("junior", "intern", "trainee", "junior developer", "junior software engineer");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Override
    public JobSource getSource() { return JobSource.BULLDOGJOB; }

    @Override
    public List<RawJobOffer> scrape() {
        Request request = new Request.Builder()
                .url(API_URL)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36")
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "pl-PL,pl;q=0.9,en;q=0.8")
                .header("Referer", "https://bulldogjob.pl/companies/jobs")
                .header("Origin", "https://bulldogjob.pl")
                .header("sec-fetch-dest", "empty")
                .header("sec-fetch-mode", "cors")
                .header("sec-fetch-site", "same-origin")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                log.warn("Bulldogjob API returned {}", response.code());
                return List.of();
            }

            JsonNode root = objectMapper.readTree(body.string());
            List<RawJobOffer> result = new ArrayList<>();

            // ⚠️ Field path depends on actual API response.
            // Bulldogjob typically wraps listings in a "jobs" or "data" array.
            JsonNode jobs = root.path("jobs");
            if (!jobs.isArray() || jobs.isEmpty()) jobs = root.path("data");
            if (!jobs.isArray() || jobs.isEmpty()) jobs = root.isArray() ? root : jobs;

            for (JsonNode job : jobs) {
                if (!isJuniorOrIntern(job)) continue;
                if (!isTriCityOrRemote(job)) continue;

                String title   = job.path("title").asText(job.path("name").asText(""));
                String company = job.path("company").path("name").asText(
                                 job.path("companyName").asText("Unknown"));
                String city    = job.path("city").asText(
                                 job.path("location").asText("Poland"));
                String slug    = job.path("slug").asText(job.path("id").asText(""));
                String url     = slug.startsWith("http") ? slug
                                 : BASE_URL + "/companies/jobs/" + slug;

                if (title.isBlank() || slug.isBlank()) continue;

                result.add(new RawJobOffer(
                        title, company, city, url,
                        title + " at " + company + " — " + city,
                        JobSource.BULLDOGJOB
                ));
            }
            return result;
        } catch (Exception e) {
            log.error("Bulldogjob scraper error", e);
            throw new RuntimeException("Bulldogjob scrape failed", e);
        }
    }

    private boolean isJuniorOrIntern(JsonNode job) {
        String exp = job.path("experienceLevel").asText(
                     job.path("seniority").asText(
                     job.path("level").asText(""))).toLowerCase();
        return ACCEPTED_SENIORITY.stream().anyMatch(exp::contains)
                || exp.equals("junior") || exp.equals("intern") || exp.equals("trainee");
    }

    private boolean isTriCityOrRemote(JsonNode job) {
        String city = job.path("city").asText(
                      job.path("location").asText("")).toLowerCase();
        boolean remote = job.path("remote").asBoolean(false)
                || city.contains("remote") || city.isBlank();
        return remote || ACCEPTED_CITIES.stream().anyMatch(city::contains);
    }
}
