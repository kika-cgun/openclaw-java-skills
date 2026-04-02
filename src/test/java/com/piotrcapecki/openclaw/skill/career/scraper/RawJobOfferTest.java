package com.piotrcapecki.openclaw.skill.career.scraper;

import com.piotrcapecki.openclaw.skill.career.domain.JobSource;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RawJobOfferTest {

    @Test
    void createsRawJobOffer() {
        RawJobOffer offer = new RawJobOffer(
                "Junior Java Developer", "Nordea", "Gdańsk / remote",
                "https://justjoin.it/offers/nordea-junior-java",
                "We are looking for a junior Java developer...",
                JobSource.JUSTJOINIT
        );

        assertThat(offer.title()).isEqualTo("Junior Java Developer");
        assertThat(offer.company()).isEqualTo("Nordea");
        assertThat(offer.location()).isEqualTo("Gdańsk / remote");
        assertThat(offer.url()).isEqualTo("https://justjoin.it/offers/nordea-junior-java");
        assertThat(offer.description()).isEqualTo("We are looking for a junior Java developer...");
        assertThat(offer.source()).isEqualTo(JobSource.JUSTJOINIT);
    }
}
