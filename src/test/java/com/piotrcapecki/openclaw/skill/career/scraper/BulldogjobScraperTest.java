package com.piotrcapecki.openclaw.skill.career.scraper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piotrcapecki.openclaw.skill.career.domain.JobSource;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BulldogjobScraperTest {

    @Mock OkHttpClient httpClient;

    @Test
    void parsesJuniorJavaOffersAndFiltersOutSenior() throws Exception {
        String json = """
            {
              "jobs": [
                {
                  "title": "Junior Java Developer",
                  "company": { "name": "Nordea" },
                  "city": "Gdańsk",
                  "slug": "junior-java-nordea-123",
                  "experienceLevel": "junior",
                  "remote": false
                },
                {
                  "title": "Senior Java Architect",
                  "company": { "name": "BigCorp" },
                  "city": "Warszawa",
                  "slug": "senior-java-bigcorp-456",
                  "experienceLevel": "senior",
                  "remote": false
                }
              ]
            }
            """;

        Call call = mock(Call.class);
        Response response = new Response.Builder()
                .request(new Request.Builder().url("https://bulldogjob.pl/companies/jobs/s/skills,Java/order,date").build())
                .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ResponseBody.create(json, MediaType.parse("application/json")))
                .build();
        when(httpClient.newCall(any())).thenReturn(call);
        when(call.execute()).thenReturn(response);

        BulldogjobScraper scraper = new BulldogjobScraper(httpClient, new ObjectMapper());
        List<RawJobOffer> offers = scraper.scrape();

        // Senior offer should be filtered out; Gdańsk junior should pass
        assertThat(offers).hasSize(1);
        assertThat(offers.get(0).title()).isEqualTo("Junior Java Developer");
        assertThat(offers.get(0).company()).isEqualTo("Nordea");
        assertThat(offers.get(0).location()).isEqualTo("Gdańsk");
        assertThat(offers.get(0).url()).isEqualTo("https://bulldogjob.pl/companies/jobs/junior-java-nordea-123");
        assertThat(offers.get(0).source()).isEqualTo(JobSource.BULLDOGJOB);
    }

    @Test
    void returnsEmptyListOnNonSuccessResponse() throws Exception {
        Call call = mock(Call.class);
        Response response = new Response.Builder()
                .request(new Request.Builder().url("https://bulldogjob.pl/companies/jobs/s/skills,Java/order,date").build())
                .protocol(Protocol.HTTP_1_1).code(403).message("Forbidden")
                .body(ResponseBody.create("", MediaType.parse("text/html")))
                .build();
        when(httpClient.newCall(any())).thenReturn(call);
        when(call.execute()).thenReturn(response);

        BulldogjobScraper scraper = new BulldogjobScraper(httpClient, new ObjectMapper());
        List<RawJobOffer> offers = scraper.scrape();

        assertThat(offers).isEmpty();
    }
}
