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
public class DeloitteJobsScraper implements JobScraper {

    private static final String BASE_URL   = "https://jobsearch.deloitte.com";
    private static final String SEARCH_URL = BASE_URL + "/jobs#countries=Poland&category=Technology";
    // ⚠️ Verify selectors — Deloitte may render via JavaScript; inspect XHR calls if Jsoup returns empty
    private static final String OFFER_SEL    = "div.apply-grid-card";
    private static final String TITLE_SEL    = "a.job-title";
    private static final String LOCATION_SEL = "span.job-location";

    @Override
    public JobSource getSource() { return JobSource.DELOITTE; }

    @Override
    public List<RawJobOffer> scrape() {
        try {
            Document doc = fetchDocument();
            List<RawJobOffer> result = new ArrayList<>();
            for (Element el : doc.select(OFFER_SEL)) {
                Element titleEl = el.selectFirst(TITLE_SEL);
                if (titleEl == null) continue;
                String title = titleEl.text();
                String href = titleEl.attr("href");
                String url = href.startsWith("http") ? href : BASE_URL + href;
                String location = el.select(LOCATION_SEL).text();
                if (location.isBlank()) location = "Poland";
                result.add(new RawJobOffer(
                        title,
                        "Deloitte",
                        location,
                        url,
                        title + " at Deloitte — " + location,
                        JobSource.DELOITTE));
            }
            return result;
        } catch (Exception e) {
            log.error("Deloitte scraper error", e);
            throw new RuntimeException("Deloitte scrape failed", e);
        }
    }

    protected Document fetchDocument() throws Exception {
        return Jsoup.connect(SEARCH_URL).userAgent("Mozilla/5.0").timeout(15_000).get();
    }
}
