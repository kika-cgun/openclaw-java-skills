package com.piotrcapecki.openclaw.skill.career.scraper;

import com.piotrcapecki.openclaw.skill.career.domain.JobSource;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeloitteJobsScraperTest {

    @Test
    void parsesJobsFromHtml() {
        String html = """
            <div class="apply-grid-card">
              <a class="job-title" href="/jobs/java-dev-123">Java Developer</a>
              <span class="job-location">Warszawa, Polska (Remote)</span>
            </div>
            """;

        DeloitteJobsScraper scraper = new DeloitteJobsScraper() {
            @Override protected Document fetchDocument() { return Jsoup.parse(html); }
        };

        List<RawJobOffer> offers = scraper.scrape();

        assertThat(offers).hasSize(1);
        assertThat(offers.get(0).title()).isEqualTo("Java Developer");
        assertThat(offers.get(0).company()).isEqualTo("Deloitte");
        assertThat(offers.get(0).source()).isEqualTo(JobSource.DELOITTE);
        assertThat(offers.get(0).url()).isEqualTo("https://jobsearch.deloitte.com/jobs/java-dev-123");
    }
}
