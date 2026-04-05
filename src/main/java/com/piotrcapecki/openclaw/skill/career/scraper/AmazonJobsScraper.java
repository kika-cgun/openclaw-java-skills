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

@Component
@RequiredArgsConstructor
@Slf4j
public class AmazonJobsScraper implements JobScraper {

    // Official Amazon Jobs JSON API — used by their own search frontend
    private static final String BASE_URL = "https://www.amazon.jobs";
    private static final String API_URL  = BASE_URL +
            "/en/search.json?base_query=java&loc_query=Poland" +
            "&category%5B%5D=software-development" +
            "&experience_ids%5B%5D=entry-level" +
            "&result_limit=25";

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Override
    public JobSource getSource() { return JobSource.AMAZON; }

    @Override
    public List<RawJobOffer> scrape() {
        Request request = new Request.Builder()
                .url(API_URL)
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                log.warn("Amazon Jobs API returned {}", response.code());
                return List.of();
            }

            JsonNode root = objectMapper.readTree(body.string());
            List<RawJobOffer> result = new ArrayList<>();

            for (JsonNode job : root.path("jobs")) {
                String title    = job.path("title").asText();
                String location = job.path("location").asText("Poland");
                String jobPath  = job.path("job_path").asText();
                String url      = jobPath.startsWith("http") ? jobPath : BASE_URL + jobPath;
                String desc     = job.path("description_short").asText(title + " at Amazon");

                result.add(new RawJobOffer(title, "Amazon", location, url, desc, JobSource.AMAZON));
            }
            return result;
        } catch (Exception e) {
            log.error("Amazon Jobs scraper error", e);
            throw new RuntimeException("Amazon Jobs scrape failed", e);
        }
    }
}
