package com.piotrcapecki.openclaw.skill.career.scraper;

import com.piotrcapecki.openclaw.skill.career.domain.JobSource;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AmazonJobsScraperTest {

    @Test
    void parsesJobsFromHtml() {
        String html = """
            <div class="job-tile">
              <h3 class="job-title"><a href="/pl/jobs/123">Software Dev Engineer I</a></h3>
              <div class="location-and-id"><span>Gdańsk, Poland</span></div>
            </div>
            """;

        AmazonJobsScraper scraper = new AmazonJobsScraper() {
            @Override protected Document fetchDocument() { return Jsoup.parse(html); }
        };

        List<RawJobOffer> offers = scraper.scrape();

        assertThat(offers).hasSize(1);
        assertThat(offers.get(0).title()).isEqualTo("Software Dev Engineer I");
        assertThat(offers.get(0).company()).isEqualTo("Amazon");
        assertThat(offers.get(0).source()).isEqualTo(JobSource.AMAZON);
        assertThat(offers.get(0).url()).isEqualTo("https://www.amazon.jobs/pl/jobs/123");
    }
}
