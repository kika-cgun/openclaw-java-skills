package com.piotrcapecki.openclaw.skill.career.scraper;

import com.piotrcapecki.openclaw.skill.career.domain.JobSource;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JitTeamScraperTest {

    @Test
    void parsesOffersFromHtmlWhenJobsPresent() {
        // HTML matching the updated selectors: article.JobOffer, h2, [class*='location'], a[href]
        String html = """
            <article class="JobOffer">
              <h2>Junior Java Developer</h2>
              <span class="location">Gdańsk</span>
              <a href="/join/junior-java-dev-123">Apply</a>
            </article>
            """;

        JitTeamScraper scraper = new JitTeamScraper() {
            @Override protected Document fetchDocument() { return Jsoup.parse(html); }
        };

        List<RawJobOffer> offers = scraper.scrape();

        assertThat(offers).hasSize(1);
        assertThat(offers.get(0).title()).isEqualTo("Junior Java Developer");
        assertThat(offers.get(0).company()).isEqualTo("JIT.team");
        assertThat(offers.get(0).location()).isEqualTo("Gdańsk");
        assertThat(offers.get(0).url()).isEqualTo("https://jit.team/join/junior-java-dev-123");
        assertThat(offers.get(0).source()).isEqualTo(JobSource.JIT);
    }

    @Test
    void returnsEmptyListGracefullyWhenNoOffersPresent() {
        // Mirrors the live page state: "We have no such active offers"
        String html = "<div class=\"empty-state\">We have no such active offers</div>";

        JitTeamScraper scraper = new JitTeamScraper() {
            @Override protected Document fetchDocument() { return Jsoup.parse(html); }
        };

        List<RawJobOffer> offers = scraper.scrape();

        assertThat(offers).isEmpty();
    }
}
