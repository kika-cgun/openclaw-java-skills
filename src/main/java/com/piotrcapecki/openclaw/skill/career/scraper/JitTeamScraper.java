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
    private static final String JOBS_URL  = BASE_URL + "/join";

    // ⚠️ jit.team/join is a React SPA — Jsoup fetches only the static shell,
    //    so these selectors target the server-side-rendered fallback markup.
    //    Currently (2026) the page reports "We have no such active offers"
    //    when no positions are open → scraper will return an empty list without crashing.
    //    Once active offers appear, verify selectors against the rendered DOM.
    private static final String OFFER_SEL    = "article.JobOffer, div[class*='JobOffer'], li[class*='job']";
    private static final String TITLE_SEL    = "h2, h3, [class*='title']";
    private static final String LOCATION_SEL = "[class*='location'], [class*='city']";
    private static final String LINK_SEL     = "a[href]";

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
                String location = el.select(LOCATION_SEL).text();
                String href = el.select(LINK_SEL).attr("href");
                String url = href.startsWith("http") ? href : BASE_URL + href;
                result.add(new RawJobOffer(
                        title,
                        "JIT.team",
                        location.isBlank() ? "Poland" : location,
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
