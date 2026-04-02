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
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NoFluffJobsScraperTest {

    @Mock OkHttpClient httpClient;

    @Test
    void parsesJuniorJavaOffersFromResponse() throws Exception {
        String json = """
            {
              "postings": [{
                "id": "javadev-123",
                "name": "Java Developer",
                "company": { "name": "Accenture" },
                "location": { "places": [{ "city": "Gdańsk" }] },
                "seniority": ["junior"],
                "remote": false
              }]
            }
            """;

        Call call = mock(Call.class);
        Response response = new Response.Builder()
                .request(new Request.Builder().url("https://nofluffjobs.com/api/v2/postings").build())
                .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ResponseBody.create(json, MediaType.parse("application/json")))
                .build();
        when(httpClient.newCall(any())).thenReturn(call);
        when(call.execute()).thenReturn(response);

        NoFluffJobsScraper scraper = new NoFluffJobsScraper(httpClient, new ObjectMapper());
        List<RawJobOffer> offers = scraper.scrape();

        assertThat(offers).hasSize(1);
        assertThat(offers.get(0).company()).isEqualTo("Accenture");
        assertThat(offers.get(0).source()).isEqualTo(JobSource.NOFLUFFJOBS);
        assertThat(offers.get(0).url()).isEqualTo("https://nofluffjobs.com/pl/job/javadev-123");
    }
}
