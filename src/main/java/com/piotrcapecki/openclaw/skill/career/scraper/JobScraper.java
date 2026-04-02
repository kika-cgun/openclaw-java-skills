package com.piotrcapecki.openclaw.skill.career.scraper;

import com.piotrcapecki.openclaw.skill.career.domain.JobSource;
import java.util.List;

public interface JobScraper {
    JobSource getSource();
    List<RawJobOffer> scrape();
}
