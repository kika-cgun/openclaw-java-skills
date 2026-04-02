package com.piotrcapecki.openclaw.skill.career.scraper;

import com.piotrcapecki.openclaw.skill.career.domain.JobSource;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JitTeamScraperTest {

    @Test
    void parsesOffersFromHtml() {
        String html = """
            <div class="job-offer">
              <h2 class="job-title">Junior Java Developer</h2>
              <span class="company">JIT.team</span>
              <span class="location">Gdańsk</span>
              <a class="job-link" href="/praca/junior-java">Apply</a>
            </div>
            """;

        JitTeamScraper scraper = new JitTeamScraper() {
            @Override protected Document fetchDocument() { return Jsoup.parse(html); }
        };

        List<RawJobOffer> offers = scraper.scrape();

        assertThat(offers).hasSize(1);
        assertThat(offers.get(0).title()).isEqualTo("Junior Java Developer");
        assertThat(offers.get(0).company()).isEqualTo("JIT.team");
        assertThat(offers.get(0).url()).isEqualTo("https://jit.team/praca/junior-java");
    }
}
