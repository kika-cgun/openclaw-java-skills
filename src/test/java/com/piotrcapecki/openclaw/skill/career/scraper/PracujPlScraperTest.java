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
class PracujPlScraperTest {

    @Mock OkHttpClient httpClient;

    @Test
    void parsesOffersFromGroupedOffersStructure() throws Exception {
        String json = """
            {
              "groupedOffers": [
                {
                  "jobTitle": "Junior Java Developer",
                  "companyName": "Capgemini",
                  "offers": [{
                    "displayWorkplace": "Gdańsk",
                    "offerAbsoluteUri": "https://it.pracuj.pl/praca/junior-java-developer,capgemini,1234"
                  }]
                },
                {
                  "jobTitle": "",
                  "companyName": "NoTitle Corp",
                  "offers": [{ "offerAbsoluteUri": "" }]
                }
              ]
            }
            """;

        Call call = mock(Call.class);
        Response response = new Response.Builder()
                .request(new Request.Builder().url("https://it.pracuj.pl/api/v1/offers").build())
                .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ResponseBody.create(json, MediaType.parse("application/json")))
                .build();
        when(httpClient.newCall(any())).thenReturn(call);
        when(call.execute()).thenReturn(response);

        PracujPlScraper scraper = new PracujPlScraper(httpClient, new ObjectMapper());
        List<RawJobOffer> offers = scraper.scrape();

        // blank title/url entry should be skipped
        assertThat(offers).hasSize(1);
        assertThat(offers.get(0).title()).isEqualTo("Junior Java Developer");
        assertThat(offers.get(0).company()).isEqualTo("Capgemini");
        assertThat(offers.get(0).location()).isEqualTo("Gdańsk");
        assertThat(offers.get(0).url()).isEqualTo("https://it.pracuj.pl/praca/junior-java-developer,capgemini,1234");
        assertThat(offers.get(0).source()).isEqualTo(JobSource.PRACUJ);
    }

    @Test
    void returnsEmptyListOnNonSuccessResponse() throws Exception {
        Call call = mock(Call.class);
        Response response = new Response.Builder()
                .request(new Request.Builder().url("https://it.pracuj.pl/api/v1/offers").build())
                .protocol(Protocol.HTTP_1_1).code(403).message("Forbidden")
                .body(ResponseBody.create("", MediaType.parse("text/html")))
                .build();
        when(httpClient.newCall(any())).thenReturn(call);
        when(call.execute()).thenReturn(response);

        PracujPlScraper scraper = new PracujPlScraper(httpClient, new ObjectMapper());
        List<RawJobOffer> offers = scraper.scrape();

        assertThat(offers).isEmpty();
    }
}
