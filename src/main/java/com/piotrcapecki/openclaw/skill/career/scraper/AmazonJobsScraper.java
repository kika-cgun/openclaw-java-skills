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
public class AmazonJobsScraper implements JobScraper {

    private static final String BASE_URL   = "https://www.amazon.jobs";
    private static final String SEARCH_URL = BASE_URL +
            "/pl/search?country=POL&base_query=java&category[]=software-development&experience_ids[]=entry-level";
    // ⚠️ Verify selectors against the live page before production
    private static final String OFFER_SEL    = "div.job-tile";
    private static final String TITLE_SEL    = "h3.job-title a";
    private static final String LOCATION_SEL = "div.location-and-id span";

    @Override
    public JobSource getSource() { return JobSource.AMAZON; }

    @Override
    public List<RawJobOffer> scrape() {
        try {
            Document doc = fetchDocument();
            List<RawJobOffer> result = new ArrayList<>();
            for (Element el : doc.select(OFFER_SEL)) {
                Element titleLink = el.selectFirst(TITLE_SEL);
                if (titleLink == null) continue;
                String title = titleLink.text();
                String href = titleLink.attr("href");
                String url = href.startsWith("http") ? href : BASE_URL + href;
                String location = el.select(LOCATION_SEL).text();
                if (location.isBlank()) location = "Poland";
                result.add(new RawJobOffer(
                        title,
                        "Amazon",
                        location,
                        url,
                        title + " at Amazon — " + location,
                        JobSource.AMAZON));
            }
            return result;
        } catch (Exception e) {
            log.error("Amazon Jobs scraper error", e);
            throw new RuntimeException("Amazon Jobs scrape failed", e);
        }
    }

    protected Document fetchDocument() throws Exception {
        return Jsoup.connect(SEARCH_URL).userAgent("Mozilla/5.0").timeout(15_000).get();
    }
}
