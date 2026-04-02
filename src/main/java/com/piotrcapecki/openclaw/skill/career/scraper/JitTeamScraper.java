package com.piotrcapecki.openclaw.skill.career.scraper;

import com.piotrcapecki.openclaw.skill.career.domain.JobSource;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class JitTeamScraper implements JobScraper {

    private static final String BASE_URL  = "https://jit.team";
    private static final String JOBS_URL  = BASE_URL + "/praca";
    // ⚠️ Verify selectors against the live page before production
    private static final String OFFER_SEL    = "div.job-offer";
    private static final String TITLE_SEL    = "h2.job-title";
    private static final String COMPANY_SEL  = "span.company";
    private static final String LOCATION_SEL = "span.location";
    private static final String LINK_SEL     = "a.job-link";

    @Override
    public JobSource getSource() { return JobSource.JIT; }

    @Override
    public List<RawJobOffer> scrape() {
        try {
            Document doc = fetchDocument();
            List<RawJobOffer> result = new ArrayList<>();
            for (Element el : doc.select(OFFER_SEL)) {
                String title = el.select(TITLE_SEL).text();
                if (title.isBlank()) continue;
                String company = el.select(COMPANY_SEL).text();
                String location = el.select(LOCATION_SEL).text();
                String href = el.select(LINK_SEL).attr("href");
                String url = href.startsWith("http") ? href : BASE_URL + href;
                result.add(new RawJobOffer(
                        title,
                        company.isBlank() ? "JIT.team" : company,
                        location.isBlank() ? "Gdańsk" : location,
                        url,
                        title + " — " + location,
                        JobSource.JIT));
            }
            return result;
        } catch (Exception e) {
            log.error("JIT.team scraper error", e);
            throw new RuntimeException("JIT.team scrape failed", e);
        }
    }

    protected Document fetchDocument() throws Exception {
        return Jsoup.connect(JOBS_URL).userAgent("Mozilla/5.0").timeout(15_000).get();
    }
}
