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
class JustJoinItScraperTest {

    @Mock OkHttpClient httpClient;

    @Test
    void parsesJavaJuniorOffersAndFiltersOutOthers() throws Exception {
        String json = """
            [
              {
                "slug": "nordea-junior-java",
                "title": "Junior Java Developer",
                "company_name": "Nordea",
                "city": "Gdańsk",
                "workplace_type": "remote",
                "marker_icon": "java",
                "experience_level": "junior"
              },
              {
                "slug": "company-senior-php",
                "title": "Senior PHP Developer",
                "company_name": "SomeCompany",
                "city": "Warszawa",
                "workplace_type": "office",
                "marker_icon": "php",
                "experience_level": "senior"
              }
            ]
            """;

        Call call = mock(Call.class);
        Response response = new Response.Builder()
                .request(new Request.Builder().url("https://justjoin.it/api/offers").build())
                .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ResponseBody.create(json, MediaType.parse("application/json")))
                .build();
        when(httpClient.newCall(any())).thenReturn(call);
        when(call.execute()).thenReturn(response);

        JustJoinItScraper scraper = new JustJoinItScraper(httpClient, new ObjectMapper());
        List<RawJobOffer> offers = scraper.scrape();

        assertThat(offers).hasSize(1);
        assertThat(offers.get(0).title()).isEqualTo("Junior Java Developer");
        assertThat(offers.get(0).source()).isEqualTo(JobSource.JUSTJOINIT);
        assertThat(offers.get(0).url()).isEqualTo("https://justjoin.it/offers/nordea-junior-java");
    }
}
