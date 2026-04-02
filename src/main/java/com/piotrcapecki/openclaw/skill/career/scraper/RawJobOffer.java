package com.piotrcapecki.openclaw.skill.career.scraper;

import com.piotrcapecki.openclaw.skill.career.domain.JobSource;

public record RawJobOffer(
        String title,
        String company,
        String location,
        String url,
        String description,
        JobSource source
) {}
