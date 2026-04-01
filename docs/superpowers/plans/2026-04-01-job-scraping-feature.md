# Job Scraping & Scoring Feature — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a daily job scraping, AI scoring, and Telegram notification pipeline inside the OpenClaw Career Agent Spring Boot application.

**Architecture:** Five `JobScraper` implementations are auto-discovered by Spring and called by `JobIngestionService`, which deduplicates against PostgreSQL and stores new offers as `PENDING_SCORE`. `ScoringService` then sends all pending offers to Claude in one batch prompt and updates their scores. `TelegramNotifier` sends a single daily digest grouped by score.

**Tech Stack:** Java 21, Spring Boot 4, Spring Data JPA, Flyway, PostgreSQL, Jsoup 1.17.2, OkHttp 4.12.0, Anthropic Java SDK (`com.anthropic:anthropic-java` — verify latest version on Maven Central), Lombok, JUnit 5, Mockito, AssertJ.

---

## File Map

```
src/main/java/com/piotrcapecki/openclaw_career_agent/
├── config/
│   ├── AppConfig.java                   # Anthropic client bean, ObjectMapper bean
│   └── SecurityConfig.java              # API key header filter
├── domain/
│   ├── JobOffer.java                    # JPA entity
│   ├── UserProfile.java                 # JPA entity
│   ├── ScrapeRun.java                   # JPA entity
│   ├── JobSource.java                   # enum: JUSTJOINIT | NOFLUFFJOBS | JIT | AMAZON | DELOITTE
│   ├── OfferScore.java                  # enum: PENDING_SCORE | STRONG | MEDIUM | SKIP
│   └── StringListConverter.java         # JPA AttributeConverter<List<String>, String>
├── repository/
│   ├── JobOfferRepository.java
│   ├── UserProfileRepository.java
│   └── ScrapeRunRepository.java
├── scraper/
│   ├── JobScraper.java                  # interface
│   ├── RawJobOffer.java                 # record (title, company, location, url, description, source)
│   ├── JustJoinItScraper.java           # REST API
│   ├── NoFluffJobsScraper.java          # REST API
│   ├── JitTeamScraper.java             # Jsoup
│   ├── AmazonJobsScraper.java           # Jsoup
│   └── DeloitteJobsScraper.java         # Jsoup
├── service/
│   ├── JobIngestionService.java         # orchestration + deduplication
│   ├── ScoringService.java              # Claude batch scoring
│   └── TelegramNotifier.java            # Telegram Bot API
├── scheduler/
│   └── DailyJobScheduler.java           # @Scheduled cron
├── api/
│   ├── ProfileController.java           # GET/PATCH /api/profile
│   ├── OffersController.java            # GET /api/offers, /api/offers/{id}
│   └── ScrapeController.java            # POST /api/scrape/run, GET /api/scrape/runs
└── dto/
    ├── UserProfileDto.java
    ├── JobOfferDto.java
    ├── ScrapeRunDto.java
    └── ScoreResultDto.java              # internal record for Claude JSON response

src/main/resources/
├── application.yaml
└── db/migration/
    ├── V1__create_user_profile.sql
    ├── V2__create_job_offers.sql
    └── V3__create_scrape_runs.sql

src/test/java/com/piotrcapecki/openclaw_career_agent/
├── scraper/
│   ├── JustJoinItScraperTest.java
│   ├── NoFluffJobsScraperTest.java
│   ├── JitTeamScraperTest.java
│   ├── AmazonJobsScraperTest.java
│   └── DeloitteJobsScraperTest.java
├── service/
│   ├── JobIngestionServiceTest.java
│   ├── ScoringServiceTest.java
│   └── TelegramNotifierTest.java
└── api/
    ├── ProfileControllerTest.java
    ├── OffersControllerTest.java
    └── ScrapeControllerTest.java
```

---

## Task 1: Foundation — Dependencies & Configuration

**Files:**
- Modify: `build.gradle.kts`
- Modify: `src/main/resources/application.yaml`
- Create: `src/main/java/com/piotrcapecki/openclaw_career_agent/config/AppConfig.java`

- [ ] **Step 1: Add dependencies to `build.gradle.kts`**

Replace the entire `dependencies` block:

```kotlin
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.jsoup:jsoup:1.17.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Check Maven Central for the latest com.anthropic:anthropic-java version
    implementation("com.anthropic:anthropic-java:1.3.0")
    compileOnly("org.projectlombok:lombok")
    runtimeOnly("org.postgresql:postgresql")
    annotationProcessor("org.projectlombok:lombok")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testCompileOnly("org.projectlombok:lombok")
    testAnnotationProcessor("org.projectlombok:lombok")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
```

Also add `@EnableScheduling` to the main application class `OpenClawCareerAgentApplication.java`:

```java
@SpringBootApplication
@EnableScheduling
public class OpenClawCareerAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(OpenClawCareerAgentApplication.class, args);
    }
}
```

- [ ] **Step 2: Populate `application.yaml`**

```yaml
spring:
  application:
    name: OpenClaw Career Agent
  datasource:
    url: jdbc:postgresql://localhost:5432/openclaw
    username: ${DB_USERNAME:openclaw}
    password: ${DB_PASSWORD:openclaw}
    driver-class-name: org.postgresql.Driver
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
  flyway:
    enabled: true
    locations: classpath:db/migration

anthropic:
  api-key: ${ANTHROPIC_API_KEY}

telegram:
  bot-token: ${TELEGRAM_BOT_TOKEN}
  chat-id: ${TELEGRAM_CHAT_ID}

app:
  api-key: ${APP_API_KEY:changeme}
```

- [ ] **Step 3: Create `AppConfig.java`**

```java
package com.piotrcapecki.openclaw_career_agent.config;

import com.anthropic.client.Anthropic;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfig {

    @Value("${anthropic.api-key}")
    private String anthropicApiKey;

    @Bean
    public Anthropic anthropicClient() {
        return AnthropicOkHttpClient.builder()
                .apiKey(anthropicApiKey)
                .build();
    }
}
```

- [ ] **Step 4: Verify the project compiles**

```bash
cd "/Users/c-gun77/Developer/AntiGravity/OpenClaw Career Agent"
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: Commit**

```bash
git add build.gradle.kts src/main/resources/application.yaml \
  src/main/java/com/piotrcapecki/openclaw_career_agent/OpenClawCareerAgentApplication.java \
  src/main/java/com/piotrcapecki/openclaw_career_agent/config/AppConfig.java
git commit -m "chore: add dependencies and base configuration"
```

---

## Task 2: Database — Flyway Migrations

**Files:**
- Create: `src/main/resources/db/migration/V1__create_user_profile.sql`
- Create: `src/main/resources/db/migration/V2__create_job_offers.sql`
- Create: `src/main/resources/db/migration/V3__create_scrape_runs.sql`

- [ ] **Step 1: Create `V1__create_user_profile.sql`**

```sql
CREATE TABLE user_profile (
    id          BIGSERIAL PRIMARY KEY,
    stack       TEXT,
    level       TEXT,
    locations   TEXT,
    preferences TEXT,
    updated_at  TIMESTAMP
);
```

> `stack`, `level`, `locations` are stored as comma-separated strings and converted to `List<String>` in Java via a JPA `AttributeConverter`.

- [ ] **Step 2: Create `V2__create_job_offers.sql`**

```sql
CREATE TABLE job_offers (
    id           UUID PRIMARY KEY,
    source       VARCHAR(50)  NOT NULL,
    external_id  VARCHAR(64)  NOT NULL UNIQUE,
    title        VARCHAR(255),
    company      VARCHAR(255),
    location     VARCHAR(255),
    url          TEXT,
    description  TEXT,
    score        VARCHAR(20)  NOT NULL DEFAULT 'PENDING_SCORE',
    score_reason TEXT,
    found_at     TIMESTAMP,
    sent_at      TIMESTAMP
);

CREATE INDEX idx_job_offers_score    ON job_offers(score);
CREATE INDEX idx_job_offers_sent_at  ON job_offers(sent_at);
```

- [ ] **Step 3: Create `V3__create_scrape_runs.sql`**

```sql
CREATE TABLE scrape_runs (
    id               UUID PRIMARY KEY,
    started_at       TIMESTAMP,
    finished_at      TIMESTAMP,
    new_offers_count INT,
    status           VARCHAR(20)
);
```

- [ ] **Step 4: Verify migrations apply**

Start a local PostgreSQL instance and create the database, then run:

```bash
./gradlew bootRun
```

Expected: application starts, Flyway applies V1, V2, V3 with `Successfully applied 3 migrations`.
Stop the app with `Ctrl+C`.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/
git commit -m "feat: add Flyway migrations for user_profile, job_offers, scrape_runs"
```

---

## Task 3: Domain — Entities, Enums & Converter

**Files:**
- Create: `src/main/java/com/piotrcapecki/openclaw_career_agent/domain/JobSource.java`
- Create: `src/main/java/com/piotrcapecki/openclaw_career_agent/domain/OfferScore.java`
- Create: `src/main/java/com/piotrcapecki/openclaw_career_agent/domain/StringListConverter.java`
- Create: `src/main/java/com/piotrcapecki/openclaw_career_agent/domain/UserProfile.java`
- Create: `src/main/java/com/piotrcapecki/openclaw_career_agent/domain/JobOffer.java`
- Create: `src/main/java/com/piotrcapecki/openclaw_career_agent/domain/ScrapeRun.java`

- [ ] **Step 1: Write tests for `StringListConverter`**

Create `src/test/java/com/piotrcapecki/openclaw_career_agent/domain/StringListConverterTest.java`:

```java
package com.piotrcapecki.openclaw_career_agent.domain;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class StringListConverterTest {

    private final StringListConverter converter = new StringListConverter();

    @Test
    void convertsToDatabaseColumn() {
        String result = converter.convertToDatabaseColumn(List.of("Java", "Spring Boot", "SQL"));
        assertThat(result).isEqualTo("Java,Spring Boot,SQL");
    }

    @Test
    void convertsNullToDatabaseColumn() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
    }

    @Test
    void convertsToEntityAttribute() {
        List<String> result = converter.convertToEntityAttribute("Java,Spring Boot,SQL");
        assertThat(result).containsExactly("Java", "Spring Boot", "SQL");
    }

    @Test
    void convertsNullToEmptyList() {
        assertThat(converter.convertToEntityAttribute(null)).isEmpty();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw_career_agent.domain.StringListConverterTest"
```

Expected: FAIL — `StringListConverter` does not exist yet.

- [ ] **Step 3: Create enums**

`JobSource.java`:
```java
package com.piotrcapecki.openclaw_career_agent.domain;

public enum JobSource {
    JUSTJOINIT, NOFLUFFJOBS, JIT, AMAZON, DELOITTE
}
```

`OfferScore.java`:
```java
package com.piotrcapecki.openclaw_career_agent.domain;

public enum OfferScore {
    PENDING_SCORE, STRONG, MEDIUM, SKIP
}
```

- [ ] **Step 4: Create `StringListConverter.java`**

```java
package com.piotrcapecki.openclaw_career_agent.domain;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import java.util.List;

@Converter
public class StringListConverter implements AttributeConverter<List<String>, String> {

    @Override
    public String convertToDatabaseColumn(List<String> list) {
        if (list == null) return null;
        return String.join(",", list);
    }

    @Override
    public List<String> convertToEntityAttribute(String data) {
        if (data == null || data.isBlank()) return List.of();
        return Arrays.asList(data.split(","));
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw_career_agent.domain.StringListConverterTest"
```

Expected: PASS

- [ ] **Step 6: Create `UserProfile.java`**

```java
package com.piotrcapecki.openclaw_career_agent.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "user_profile")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Convert(converter = StringListConverter.class)
    @Column(columnDefinition = "TEXT")
    private List<String> stack;

    @Convert(converter = StringListConverter.class)
    @Column(columnDefinition = "TEXT")
    private List<String> level;

    @Convert(converter = StringListConverter.class)
    @Column(columnDefinition = "TEXT")
    private List<String> locations;

    @Column(columnDefinition = "TEXT")
    private String preferences;

    private LocalDateTime updatedAt;
}
```

- [ ] **Step 7: Create `JobOffer.java`**

```java
package com.piotrcapecki.openclaw_career_agent.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "job_offers")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JobOffer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Enumerated(EnumType.STRING)
    private JobSource source;

    @Column(unique = true)
    private String externalId;

    private String title;
    private String company;
    private String location;

    @Column(columnDefinition = "TEXT")
    private String url;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private OfferScore score = OfferScore.PENDING_SCORE;

    @Column(columnDefinition = "TEXT")
    private String scoreReason;

    private LocalDateTime foundAt;
    private LocalDateTime sentAt;
}
```

- [ ] **Step 8: Create `ScrapeRun.java`**

```java
package com.piotrcapecki.openclaw_career_agent.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "scrape_runs")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScrapeRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Integer newOffersCount;
    private String status; // SUCCESS | PARTIAL | FAILED
}
```

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/piotrcapecki/openclaw_career_agent/domain/ \
  src/test/java/com/piotrcapecki/openclaw_career_agent/domain/
git commit -m "feat: add domain entities, enums, and StringListConverter"
```

---

## Task 4: Domain — JPA Repositories

**Files:**
- Create: `src/main/java/com/piotrcapecki/openclaw_career_agent/repository/JobOfferRepository.java`
- Create: `src/main/java/com/piotrcapecki/openclaw_career_agent/repository/UserProfileRepository.java`
- Create: `src/main/java/com/piotrcapecki/openclaw_career_agent/repository/ScrapeRunRepository.java`

- [ ] **Step 1: Create `UserProfileRepository.java`**

```java
package com.piotrcapecki.openclaw_career_agent.repository;

import com.piotrcapecki.openclaw_career_agent.domain.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {
    Optional<UserProfile> findFirstByOrderByIdAsc();
}
```

- [ ] **Step 2: Create `JobOfferRepository.java`**

```java
package com.piotrcapecki.openclaw_career_agent.repository;

import com.piotrcapecki.openclaw_career_agent.domain.JobOffer;
import com.piotrcapecki.openclaw_career_agent.domain.OfferScore;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface JobOfferRepository extends JpaRepository<JobOffer, UUID> {
    boolean existsByExternalId(String externalId);
    List<JobOffer> findByScore(OfferScore score);
    List<JobOffer> findBySentAtIsNullAndScore(OfferScore score);
    List<JobOffer> findAllByOrderByFoundAtDesc();
}
```

- [ ] **Step 3: Create `ScrapeRunRepository.java`**

```java
package com.piotrcapecki.openclaw_career_agent.repository;

import com.piotrcapecki.openclaw_career_agent.domain.ScrapeRun;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ScrapeRunRepository extends JpaRepository<ScrapeRun, UUID> {
    List<ScrapeRun> findAllByOrderByStartedAtDesc();
}
```

- [ ] **Step 4: Verify the application context loads**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw_career_agent.OpenClawCareerAgentApplicationTests"
```

Expected: PASS (Spring context loads with JPA repositories wired correctly)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/piotrcapecki/openclaw_career_agent/repository/
git commit -m "feat: add Spring Data JPA repositories"
```

---

## Task 5: Scraper — Interface & RawJobOffer

**Files:**
- Create: `src/main/java/com/piotrcapecki/openclaw_career_agent/scraper/RawJobOffer.java`
- Create: `src/main/java/com/piotrcapecki/openclaw_career_agent/scraper/JobScraper.java`

- [ ] **Step 1: Write test for `RawJobOffer`**

Create `src/test/java/com/piotrcapecki/openclaw_career_agent/scraper/RawJobOfferTest.java`:

```java
package com.piotrcapecki.openclaw_career_agent.scraper;

import com.piotrcapecki.openclaw_career_agent.domain.JobSource;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RawJobOfferTest {

    @Test
    void createsRawJobOffer() {
        RawJobOffer offer = new RawJobOffer(
                "Junior Java Developer",
                "Nordea",
                "Gdańsk / remote",
                "https://justjoin.it/offers/nordea-junior-java",
                "We are looking for a junior Java developer...",
                JobSource.JUSTJOINIT
        );

        assertThat(offer.title()).isEqualTo("Junior Java Developer");
        assertThat(offer.source()).isEqualTo(JobSource.JUSTJOINIT);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw_career_agent.scraper.RawJobOfferTest"
```

Expected: FAIL — `RawJobOffer` does not exist.

- [ ] **Step 3: Create `RawJobOffer.java`**

```java
package com.piotrcapecki.openclaw_career_agent.scraper;

import com.piotrcapecki.openclaw_career_agent.domain.JobSource;

public record RawJobOffer(
        String title,
        String company,
        String location,
        String url,
        String description,
        JobSource source
) {}
```

- [ ] **Step 4: Create `JobScraper.java`**

```java
package com.piotrcapecki.openclaw_career_agent.scraper;

import com.piotrcapecki.openclaw_career_agent.domain.JobSource;
import java.util.List;

public interface JobScraper {
    JobSource getSource();
    List<RawJobOffer> scrape();
}
```

- [ ] **Step 5: Run test to verify it passes**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw_career_agent.scraper.RawJobOfferTest"
```

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/piotrcapecki/openclaw_career_agent/scraper/RawJobOffer.java \
  src/main/java/com/piotrcapecki/openclaw_career_agent/scraper/JobScraper.java \
  src/test/java/com/piotrcapecki/openclaw_career_agent/scraper/RawJobOfferTest.java
git commit -m "feat: add JobScraper interface and RawJobOffer record"
```

---

## Task 6: Service — JobIngestionService

**Files:**
- Create: `src/main/java/com/piotrcapecki/openclaw_career_agent/service/JobIngestionService.java`
- Create: `src/test/java/com/piotrcapecki/openclaw_career_agent/service/JobIngestionServiceTest.java`

- [ ] **Step 1: Write failing tests**

```java
package com.piotrcapecki.openclaw_career_agent.service;

import com.piotrcapecki.openclaw_career_agent.domain.*;
import com.piotrcapecki.openclaw_career_agent.repository.*;
import com.piotrcapecki.openclaw_career_agent.scraper.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JobIngestionServiceTest {

    @Mock JobOfferRepository jobOfferRepository;
    @Mock ScrapeRunRepository scrapeRunRepository;
    @Mock JobScraper scraperA;
    @Mock JobScraper scraperB;

    // No @InjectMocks — constructor takes List<JobScraper>, built manually in setUp()
    JobIngestionService service;

    @BeforeEach
    void setUp() {
        service = new JobIngestionService(
                List.of(scraperA, scraperB),
                jobOfferRepository,
                scrapeRunRepository
        );
    }

    @Test
    void savesNewOfferNotYetInDatabase() {
        RawJobOffer raw = new RawJobOffer(
                "Junior Java Dev", "Nordea", "Gdańsk",
                "https://example.com/job/1", "description", JobSource.JUSTJOINIT
        );
        when(scraperA.scrape()).thenReturn(List.of(raw));
        when(scraperA.getSource()).thenReturn(JobSource.JUSTJOINIT);
        when(scraperB.scrape()).thenReturn(List.of());
        when(scraperB.getSource()).thenReturn(JobSource.NOFLUFFJOBS);
        when(jobOfferRepository.existsByExternalId(anyString())).thenReturn(false);
        when(scrapeRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScrapeRun run = service.ingest();

        verify(jobOfferRepository, times(1)).save(any(JobOffer.class));
        assertThat(run.getNewOffersCount()).isEqualTo(1);
        assertThat(run.getStatus()).isEqualTo("SUCCESS");
    }

    @Test
    void skipsDuplicateOffer() {
        RawJobOffer raw = new RawJobOffer(
                "Junior Java Dev", "Nordea", "Gdańsk",
                "https://example.com/job/1", "description", JobSource.JUSTJOINIT
        );
        when(scraperA.scrape()).thenReturn(List.of(raw));
        when(scraperA.getSource()).thenReturn(JobSource.JUSTJOINIT);
        when(scraperB.scrape()).thenReturn(List.of());
        when(scraperB.getSource()).thenReturn(JobSource.NOFLUFFJOBS);
        when(jobOfferRepository.existsByExternalId(anyString())).thenReturn(true);
        when(scrapeRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScrapeRun run = service.ingest();

        verify(jobOfferRepository, never()).save(any(JobOffer.class));
        assertThat(run.getNewOffersCount()).isEqualTo(0);
    }

    @Test
    void continuesWhenOneScraperFails() {
        when(scraperA.scrape()).thenThrow(new RuntimeException("network error"));
        when(scraperA.getSource()).thenReturn(JobSource.AMAZON);
        when(scraperB.scrape()).thenReturn(List.of());
        when(scraperB.getSource()).thenReturn(JobSource.NOFLUFFJOBS);
        when(scrapeRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ScrapeRun run = service.ingest();

        assertThat(run.getStatus()).isEqualTo("PARTIAL");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw_career_agent.service.JobIngestionServiceTest"
```

Expected: FAIL — `JobIngestionService` does not exist.

- [ ] **Step 3: Implement `JobIngestionService.java`**

```java
package com.piotrcapecki.openclaw_career_agent.service;

import com.piotrcapecki.openclaw_career_agent.domain.*;
import com.piotrcapecki.openclaw_career_agent.repository.*;
import com.piotrcapecki.openclaw_career_agent.scraper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class JobIngestionService {

    private final List<JobScraper> scrapers;
    private final JobOfferRepository jobOfferRepository;
    private final ScrapeRunRepository scrapeRunRepository;

    public ScrapeRun ingest() {
        ScrapeRun run = ScrapeRun.builder()
                .startedAt(LocalDateTime.now())
                .newOffersCount(0)
                .status("SUCCESS")
                .build();

        int newOffers = 0;
        boolean hasError = false;

        for (JobScraper scraper : scrapers) {
            try {
                List<RawJobOffer> offers = scraper.scrape();
                for (RawJobOffer raw : offers) {
                    String externalId = sha256(raw.url());
                    if (!jobOfferRepository.existsByExternalId(externalId)) {
                        JobOffer offer = JobOffer.builder()
                                .externalId(externalId)
                                .source(raw.source())
                                .title(raw.title())
                                .company(raw.company())
                                .location(raw.location())
                                .url(raw.url())
                                .description(raw.description())
                                .score(OfferScore.PENDING_SCORE)
                                .foundAt(LocalDateTime.now())
                                .build();
                        jobOfferRepository.save(offer);
                        newOffers++;
                    }
                }
            } catch (Exception e) {
                log.error("Scraper {} failed: {}", scraper.getSource(), e.getMessage());
                hasError = true;
            }
        }

        run.setStatus(hasError ? "PARTIAL" : "SUCCESS");
        run.setNewOffersCount(newOffers);
        run.setFinishedAt(LocalDateTime.now());
        return scrapeRunRepository.save(run);
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw_career_agent.service.JobIngestionServiceTest"
```

Expected: all 3 tests PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/piotrcapecki/openclaw_career_agent/service/JobIngestionService.java \
  src/test/java/com/piotrcapecki/openclaw_career_agent/service/JobIngestionServiceTest.java
git commit -m "feat: implement JobIngestionService with deduplication"
```

---

## Task 7: Scraper — JustJoinIT

**Files:**
- Create: `src/main/java/com/piotrcapecki/openclaw_career_agent/scraper/JustJoinItScraper.java`
- Create: `src/test/java/com/piotrcapecki/openclaw_career_agent/scraper/JustJoinItScraperTest.java`

The JustJoinIT public API returns all offers at `https://justjoin.it/api/offers`. We filter client-side for Java offers in Trójmiasto or remote, at junior/intern level.

- [ ] **Step 1: Write failing test**

```java
package com.piotrcapecki.openclaw_career_agent.scraper;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JustJoinItScraperTest {

    @Mock OkHttpClient httpClient;
    @InjectMocks JustJoinItScraper scraper;

    @Test
    void parsesJavaJuniorOffersFromApiResponse() throws Exception {
        String json = """
            [
              {
                "slug": "nordea-junior-java",
                "title": "Junior Java Developer",
                "company_name": "Nordea",
                "city": "Gdańsk",
                "workplace_type": "remote",
                "marker_icon": "java",
                "experience_level": "junior"
              },
              {
                "slug": "company-senior-php",
                "title": "Senior PHP Developer",
                "company_name": "SomeCompany",
                "city": "Warszawa",
                "workplace_type": "office",
                "marker_icon": "php",
                "experience_level": "senior"
              }
            ]
            """;

        Call call = mock(Call.class);
        Response response = new Response.Builder()
                .request(new Request.Builder().url("https://justjoin.it/api/offers").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(json, MediaType.parse("application/json")))
                .build();

        when(httpClient.newCall(any())).thenReturn(call);
        when(call.execute()).thenReturn(response);

        scraper = new JustJoinItScraper(httpClient, new ObjectMapper());
        List<RawJobOffer> offers = scraper.scrape();

        assertThat(offers).hasSize(1);
        assertThat(offers.get(0).title()).isEqualTo("Junior Java Developer");
        assertThat(offers.get(0).company()).isEqualTo("Nordea");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw_career_agent.scraper.JustJoinItScraperTest"
```

Expected: FAIL

- [ ] **Step 3: Implement `JustJoinItScraper.java`**

```java
package com.piotrcapecki.openclaw_career_agent.scraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piotrcapecki.openclaw_career_agent.domain.JobSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class JustJoinItScraper implements JobScraper {

    private static final String API_URL = "https://justjoin.it/api/offers";
    private static final Set<String> ACCEPTED_CITIES =
            Set.of("gdańsk", "gdynia", "sopot");
    private static final Set<String> ACCEPTED_LEVELS =
            Set.of("junior", "intern", "internship");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Override
    public JobSource getSource() {
        return JobSource.JUSTJOINIT;
    }

    @Override
    public List<RawJobOffer> scrape() {
        Request request = new Request.Builder()
                .url(API_URL)
                .header("Accept", "application/json")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                log.warn("JustJoinIT API returned {}", response.code());
                return List.of();
            }

            JsonNode offers = objectMapper.readTree(response.body().string());
            List<RawJobOffer> result = new ArrayList<>();

            for (JsonNode offer : offers) {
                if (!isJava(offer)) continue;
                if (!isJuniorOrIntern(offer)) continue;
                if (!isTriCity(offer) && !isRemote(offer)) continue;

                String slug = offer.path("slug").asText();
                result.add(new RawJobOffer(
                        offer.path("title").asText(),
                        offer.path("company_name").asText(),
                        offer.path("city").asText(),
                        "https://justjoin.it/offers/" + slug,
                        offer.path("title").asText() + " at " + offer.path("company_name").asText(),
                        JobSource.JUSTJOINIT
                ));
            }
            return result;
        } catch (Exception e) {
            log.error("JustJoinIT scraper error: {}", e.getMessage());
            throw new RuntimeException("JustJoinIT scrape failed", e);
        }
    }

    private boolean isJava(JsonNode offer) {
        String icon = offer.path("marker_icon").asText().toLowerCase();
        String title = offer.path("title").asText().toLowerCase();
        return icon.contains("java") || title.contains("java");
    }

    private boolean isJuniorOrIntern(JsonNode offer) {
        String level = offer.path("experience_level").asText().toLowerCase();
        return ACCEPTED_LEVELS.contains(level);
    }

    private boolean isTriCity(JsonNode offer) {
        String city = offer.path("city").asText().toLowerCase();
        return ACCEPTED_CITIES.contains(city);
    }

    private boolean isRemote(JsonNode offer) {
        String type = offer.path("workplace_type").asText().toLowerCase();
        return type.contains("remote") || type.contains("zdalne");
    }
}
```

Also register OkHttpClient as a Spring bean in `AppConfig.java`:

```java
@Bean
public OkHttpClient okHttpClient() {
    return new OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build();
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw_career_agent.scraper.JustJoinItScraperTest"
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/piotrcapecki/openclaw_career_agent/scraper/JustJoinItScraper.java \
  src/main/java/com/piotrcapecki/openclaw_career_agent/config/AppConfig.java \
  src/test/java/com/piotrcapecki/openclaw_career_agent/scraper/JustJoinItScraperTest.java
git commit -m "feat: implement JustJoinIT scraper"
```

---

## Task 8: Scraper — No Fluff Jobs

**Files:**
- Create: `src/main/java/com/piotrcapecki/openclaw_career_agent/scraper/NoFluffJobsScraper.java`
- Create: `src/test/java/com/piotrcapecki/openclaw_career_agent/scraper/NoFluffJobsScraperTest.java`

NoFluffJobs API: `POST https://nofluffjobs.com/api/v2/postings` with a JSON filter body. Returns a list of postings.

- [ ] **Step 1: Write failing test**

```java
package com.piotrcapecki.openclaw_career_agent.scraper;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NoFluffJobsScraperTest {

    @Mock OkHttpClient httpClient;

    @Test
    void parsesJavaOffersFromApiResponse() throws Exception {
        String json = """
            {
              "postings": [
                {
                  "id": "javadev-123",
                  "name": "Java Developer",
                  "title": { "original": "Java Developer" },
                  "company": { "name": "Accenture" },
                  "location": { "places": [{ "city": "Gdańsk" }] },
                  "url": "javadev-123",
                  "seniority": ["junior"]
                }
              ]
            }
            """;

        Call call = mock(Call.class);
        Response response = new Response.Builder()
                .request(new Request.Builder().url("https://nofluffjobs.com/api/v2/postings").build())
                .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ResponseBody.create(json, MediaType.parse("application/json")))
                .build();
        when(httpClient.newCall(any())).thenReturn(call);
        when(call.execute()).thenReturn(response);

        NoFluffJobsScraper scraper = new NoFluffJobsScraper(httpClient, new ObjectMapper());
        List<RawJobOffer> offers = scraper.scrape();

        assertThat(offers).hasSize(1);
        assertThat(offers.get(0).company()).isEqualTo("Accenture");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw_career_agent.scraper.NoFluffJobsScraperTest"
```

Expected: FAIL

- [ ] **Step 3: Implement `NoFluffJobsScraper.java`**

```java
package com.piotrcapecki.openclaw_career_agent.scraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piotrcapecki.openclaw_career_agent.domain.JobSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class NoFluffJobsScraper implements JobScraper {

    private static final String API_URL = "https://nofluffjobs.com/api/v2/postings";
    private static final Set<String> ACCEPTED_CITIES =
            Set.of("gdańsk", "gdynia", "sopot", "trójmiasto");
    private static final Set<String> ACCEPTED_SENIORITY =
            Set.of("junior", "intern", "trainee");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Override
    public JobSource getSource() {
        return JobSource.NOFLUFFJOBS;
    }

    @Override
    public List<RawJobOffer> scrape() {
        try {
            String requestBody = objectMapper.writeValueAsString(java.util.Map.of(
                    "criteriaSearch", java.util.Map.of(
                            "category", List.of("backend"),
                            "technology", List.of("java")
                    )
            ));

            Request request = new Request.Builder()
                    .url(API_URL)
                    .post(RequestBody.create(requestBody, MediaType.parse("application/json")))
                    .header("Accept", "application/json")
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    log.warn("NoFluffJobs API returned {}", response.code());
                    return List.of();
                }

                JsonNode root = objectMapper.readTree(response.body().string());
                JsonNode postings = root.path("postings");
                List<RawJobOffer> result = new ArrayList<>();

                for (JsonNode posting : postings) {
                    if (!isJuniorOrIntern(posting)) continue;
                    if (!isTriCityOrRemote(posting)) continue;

                    String id = posting.path("id").asText();
                    String title = posting.path("title").path("original").asText(
                            posting.path("name").asText());
                    String company = posting.path("company").path("name").asText();
                    String location = extractLocation(posting);

                    result.add(new RawJobOffer(
                            title, company, location,
                            "https://nofluffjobs.com/pl/job/" + id,
                            title + " at " + company,
                            JobSource.NOFLUFFJOBS
                    ));
                }
                return result;
            }
        } catch (Exception e) {
            log.error("NoFluffJobs scraper error: {}", e.getMessage());
            throw new RuntimeException("NoFluffJobs scrape failed", e);
        }
    }

    private boolean isJuniorOrIntern(JsonNode posting) {
        JsonNode seniority = posting.path("seniority");
        for (JsonNode s : seniority) {
            if (ACCEPTED_SENIORITY.contains(s.asText().toLowerCase())) return true;
        }
        return false;
    }

    private boolean isTriCityOrRemote(JsonNode posting) {
        JsonNode places = posting.path("location").path("places");
        for (JsonNode place : places) {
            String city = place.path("city").asText("").toLowerCase();
            if (ACCEPTED_CITIES.contains(city)) return true;
        }
        // Check remote flag
        return posting.path("remote").asBoolean(false);
    }

    private String extractLocation(JsonNode posting) {
        JsonNode places = posting.path("location").path("places");
        if (places.size() > 0) return places.get(0).path("city").asText("N/A");
        return "remote";
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw_career_agent.scraper.NoFluffJobsScraperTest"
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/piotrcapecki/openclaw_career_agent/scraper/NoFluffJobsScraper.java \
  src/test/java/com/piotrcapecki/openclaw_career_agent/scraper/NoFluffJobsScraperTest.java
git commit -m "feat: implement NoFluffJobs scraper"
```

---

## Task 9: Scraper — JIT.team (Jsoup)

**Files:**
- Create: `src/main/java/com/piotrcapecki/openclaw_career_agent/scraper/JitTeamScraper.java`
- Create: `src/test/java/com/piotrcapecki/openclaw_career_agent/scraper/JitTeamScraperTest.java`

JIT.team careers page: `https://jit.team/praca` — scraped with Jsoup. **Verify the exact CSS selectors against the live page before running in production.**

- [ ] **Step 1: Write failing test with static HTML fixture**

```java
package com.piotrcapecki.openclaw_career_agent.scraper;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class JitTeamScraperTest {

    @Test
    void parsesOffersFromHtml() {
        String html = """
            <html><body>
              <div class="job-offer">
                <h2 class="job-title">Junior Java Developer</h2>
                <span class="company">JIT.team</span>
                <span class="location">Gdańsk</span>
                <a class="job-link" href="/praca/junior-java">Aplikuj</a>
              </div>
            </body></html>
            """;

        JitTeamScraper scraper = new JitTeamScraper() {
            @Override
            protected Document fetchDocument() {
                return Jsoup.parse(html);
            }
        };

        List<RawJobOffer> offers = scraper.scrape();

        assertThat(offers).hasSize(1);
        assertThat(offers.get(0).title()).isEqualTo("Junior Java Developer");
    }
}
```

> **Note:** The HTML fixture above is illustrative. Before finalising, visit `https://jit.team/praca` and inspect the real HTML to confirm class names. Update the fixture and selectors in the implementation accordingly.

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw_career_agent.scraper.JitTeamScraperTest"
```

Expected: FAIL

- [ ] **Step 3: Implement `JitTeamScraper.java`**

```java
package com.piotrcapecki.openclaw_career_agent.scraper;

import com.piotrcapecki.openclaw_career_agent.domain.JobSource;
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

    private static final String BASE_URL = "https://jit.team";
    private static final String JOBS_URL = BASE_URL + "/praca";

    // ⚠️ Verify these selectors against the live page before production use
    private static final String OFFER_SELECTOR    = "div.job-offer";
    private static final String TITLE_SELECTOR    = "h2.job-title";
    private static final String COMPANY_SELECTOR  = "span.company";
    private static final String LOCATION_SELECTOR = "span.location";
    private static final String LINK_SELECTOR     = "a.job-link";

    @Override
    public JobSource getSource() {
        return JobSource.JIT;
    }

    @Override
    public List<RawJobOffer> scrape() {
        try {
            Document doc = fetchDocument();
            List<RawJobOffer> result = new ArrayList<>();

            for (Element offer : doc.select(OFFER_SELECTOR)) {
                String title    = offer.select(TITLE_SELECTOR).text();
                String company  = offer.select(COMPANY_SELECTOR).text("JIT.team");
                String location = offer.select(LOCATION_SELECTOR).text("Gdańsk");
                String href     = offer.select(LINK_SELECTOR).attr("href");
                String url      = href.startsWith("http") ? href : BASE_URL + href;

                if (title.isBlank()) continue;

                result.add(new RawJobOffer(
                        title, company.isBlank() ? "JIT.team" : company,
                        location, url, title + " — " + location, JobSource.JIT
                ));
            }
            return result;
        } catch (Exception e) {
            log.error("JIT.team scraper error: {}", e.getMessage());
            throw new RuntimeException("JIT.team scrape failed", e);
        }
    }

    protected Document fetchDocument() throws Exception {
        return Jsoup.connect(JOBS_URL)
                .userAgent("Mozilla/5.0")
                .timeout(15_000)
                .get();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw_career_agent.scraper.JitTeamScraperTest"
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/piotrcapecki/openclaw_career_agent/scraper/JitTeamScraper.java \
  src/test/java/com/piotrcapecki/openclaw_career_agent/scraper/JitTeamScraperTest.java
git commit -m "feat: implement JIT.team Jsoup scraper"
```

---

## Task 10: Scraper — Amazon Jobs (Jsoup)

**Files:**
- Create: `src/main/java/com/piotrcapecki/openclaw_career_agent/scraper/AmazonJobsScraper.java`
- Create: `src/test/java/com/piotrcapecki/openclaw_career_agent/scraper/AmazonJobsScraperTest.java`

Amazon Jobs Poland search URL: `https://www.amazon.jobs/pl/search?country=POL&base_query=java&category[]=software-development&experience_ids[]=entry-level`

⚠️ **Verify the exact URL parameters and HTML selectors against the live page.**

- [ ] **Step 1: Write failing test with static HTML fixture**

```java
package com.piotrcapecki.openclaw_career_agent.scraper;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class AmazonJobsScraperTest {

    @Test
    void parsesAmazonJobsFromHtml() {
        String html = """
            <html><body>
              <div class="job-tile">
                <h3 class="job-title"><a href="/pl/jobs/123">Software Dev Engineer I</a></h3>
                <div class="location-and-id"><span>Gdańsk, Poland</span></div>
              </div>
            </body></html>
            """;

        AmazonJobsScraper scraper = new AmazonJobsScraper() {
            @Override
            protected Document fetchDocument() {
                return Jsoup.parse(html);
            }
        };

        List<RawJobOffer> offers = scraper.scrape();

        assertThat(offers).hasSize(1);
        assertThat(offers.get(0).title()).isEqualTo("Software Dev Engineer I");
        assertThat(offers.get(0).company()).isEqualTo("Amazon");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw_career_agent.scraper.AmazonJobsScraperTest"
```

Expected: FAIL

- [ ] **Step 3: Implement `AmazonJobsScraper.java`**

```java
package com.piotrcapecki.openclaw_career_agent.scraper;

import com.piotrcapecki.openclaw_career_agent.domain.JobSource;
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

    private static final String BASE_URL = "https://www.amazon.jobs";
    private static final String SEARCH_URL = BASE_URL +
            "/pl/search?country=POL&base_query=java&category[]=software-development&experience_ids[]=entry-level";

    // ⚠️ Verify selectors against the live page
    private static final String OFFER_SELECTOR    = "div.job-tile";
    private static final String TITLE_SELECTOR    = "h3.job-title a";
    private static final String LOCATION_SELECTOR = "div.location-and-id span";

    @Override
    public JobSource getSource() {
        return JobSource.AMAZON;
    }

    @Override
    public List<RawJobOffer> scrape() {
        try {
            Document doc = fetchDocument();
            List<RawJobOffer> result = new ArrayList<>();

            for (Element offer : doc.select(OFFER_SELECTOR)) {
                Element titleLink = offer.selectFirst(TITLE_SELECTOR);
                if (titleLink == null) continue;

                String title    = titleLink.text();
                String href     = titleLink.attr("href");
                String url      = href.startsWith("http") ? href : BASE_URL + href;
                String location = offer.select(LOCATION_SELECTOR).text("Poland");

                result.add(new RawJobOffer(
                        title, "Amazon", location, url,
                        title + " at Amazon — " + location, JobSource.AMAZON
                ));
            }
            return result;
        } catch (Exception e) {
            log.error("Amazon Jobs scraper error: {}", e.getMessage());
            throw new RuntimeException("Amazon Jobs scrape failed", e);
        }
    }

    protected Document fetchDocument() throws Exception {
        return Jsoup.connect(SEARCH_URL)
                .userAgent("Mozilla/5.0")
                .timeout(15_000)
                .get();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw_career_agent.scraper.AmazonJobsScraperTest"
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/piotrcapecki/openclaw_career_agent/scraper/AmazonJobsScraper.java \
  src/test/java/com/piotrcapecki/openclaw_career_agent/scraper/AmazonJobsScraperTest.java
git commit -m "feat: implement Amazon Jobs Jsoup scraper"
```

---

## Task 11: Scraper — Deloitte Jobs (Jsoup)

**Files:**
- Create: `src/main/java/com/piotrcapecki/openclaw_career_agent/scraper/DeloitteJobsScraper.java`
- Create: `src/test/java/com/piotrcapecki/openclaw_career_agent/scraper/DeloitteJobsScraperTest.java`

Deloitte Poland careers URL: `https://jobsearch.deloitte.com/jobs#countries=Poland&category=Technology`

⚠️ **Deloitte's careers page may load jobs via JavaScript. If Jsoup returns empty results, you will need to switch to Selenium or inspect the XHR API calls the page makes and call those instead.**

- [ ] **Step 1: Write failing test with static HTML fixture**

```java
package com.piotrcapecki.openclaw_career_agent.scraper;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class DeloitteJobsScraperTest {

    @Test
    void parsesDeloitteJobsFromHtml() {
        String html = """
            <html><body>
              <div class="apply-grid-card">
                <a class="job-title" href="/jobs/poland/java-developer-123">Java Developer</a>
                <span class="job-location">Warszawa, Polska (Remote)</span>
              </div>
            </body></html>
            """;

        DeloitteJobsScraper scraper = new DeloitteJobsScraper() {
            @Override
            protected Document fetchDocument() {
                return Jsoup.parse(html);
            }
        };

        List<RawJobOffer> offers = scraper.scrape();

        assertThat(offers).hasSize(1);
        assertThat(offers.get(0).company()).isEqualTo("Deloitte");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw_career_agent.scraper.DeloitteJobsScraperTest"
```

Expected: FAIL

- [ ] **Step 3: Implement `DeloitteJobsScraper.java`**

```java
package com.piotrcapecki.openclaw_career_agent.scraper;

import com.piotrcapecki.openclaw_career_agent.domain.JobSource;
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

    // ⚠️ Verify selectors against the live page — Deloitte may render via JS
    private static final String OFFER_SELECTOR    = "div.apply-grid-card";
    private static final String TITLE_SELECTOR    = "a.job-title";
    private static final String LOCATION_SELECTOR = "span.job-location";

    @Override
    public JobSource getSource() {
        return JobSource.DELOITTE;
    }

    @Override
    public List<RawJobOffer> scrape() {
        try {
            Document doc = fetchDocument();
            List<RawJobOffer> result = new ArrayList<>();

            for (Element offer : doc.select(OFFER_SELECTOR)) {
                Element titleEl = offer.selectFirst(TITLE_SELECTOR);
                if (titleEl == null) continue;

                String title    = titleEl.text();
                String href     = titleEl.attr("href");
                String url      = href.startsWith("http") ? href : BASE_URL + href;
                String location = offer.select(LOCATION_SELECTOR).text("Poland");

                result.add(new RawJobOffer(
                        title, "Deloitte", location, url,
                        title + " at Deloitte — " + location, JobSource.DELOITTE
                ));
            }
            return result;
        } catch (Exception e) {
            log.error("Deloitte scraper error: {}", e.getMessage());
            throw new RuntimeException("Deloitte scrape failed", e);
        }
    }

    protected Document fetchDocument() throws Exception {
        return Jsoup.connect(SEARCH_URL)
                .userAgent("Mozilla/5.0")
                .timeout(15_000)
                .get();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw_career_agent.scraper.DeloitteJobsScraperTest"
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/piotrcapecki/openclaw_career_agent/scraper/DeloitteJobsScraper.java \
  src/test/java/com/piotrcapecki/openclaw_career_agent/scraper/DeloitteJobsScraperTest.java
git commit -m "feat: implement Deloitte Jobs Jsoup scraper"
```

---

## Task 12: Service — ScoringService (Claude AI)

**Files:**
- Create: `src/main/java/com/piotrcapecki/openclaw_career_agent/dto/ScoreResultDto.java`
- Create: `src/main/java/com/piotrcapecki/openclaw_career_agent/service/ScoringService.java`
- Create: `src/test/java/com/piotrcapecki/openclaw_career_agent/service/ScoringServiceTest.java`

- [ ] **Step 1: Create `ScoreResultDto.java`**

```java
package com.piotrcapecki.openclaw_career_agent.dto;

public record ScoreResultDto(String offerId, String score, String reason) {}
```

- [ ] **Step 2: Write failing tests**

```java
package com.piotrcapecki.openclaw_career_agent.service;

import com.anthropic.client.Anthropic;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piotrcapecki.openclaw_career_agent.domain.*;
import com.piotrcapecki.openclaw_career_agent.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScoringServiceTest {

    @Mock Anthropic anthropicClient;
    @Mock JobOfferRepository jobOfferRepository;
    @Mock UserProfileRepository userProfileRepository;
    @InjectMocks ScoringService scoringService;

    @Test
    void updatesOfferScoreFromClaudeResponse() throws Exception {
        UUID offerId = UUID.randomUUID();
        JobOffer offer = JobOffer.builder()
                .id(offerId)
                .title("Junior Java Dev")
                .company("Nordea")
                .location("Gdańsk")
                .url("https://example.com")
                .description("Java Spring Boot role")
                .score(OfferScore.PENDING_SCORE)
                .build();

        UserProfile profile = UserProfile.builder()
                .stack(List.of("Java", "Spring Boot"))
                .level(List.of("junior"))
                .locations(List.of("Gdańsk", "remote"))
                .preferences("Backend, REST APIs")
                .build();

        String claudeResponse = """
            [{"offerId":"%s","score":"STRONG","reason":"Perfect match"}]
            """.formatted(offerId);

        when(jobOfferRepository.findByScore(OfferScore.PENDING_SCORE)).thenReturn(List.of(offer));
        when(userProfileRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(profile));

        // Mock the Anthropic SDK call
        Messages messages = mock(Messages.class);
        when(anthropicClient.messages()).thenReturn(messages);

        ContentBlock contentBlock = mock(ContentBlock.class);
        TextBlock textBlock = mock(TextBlock.class);
        when(textBlock.text()).thenReturn(claudeResponse);
        when(contentBlock.text()).thenReturn(Optional.of(textBlock));

        Message message = mock(Message.class);
        when(message.content()).thenReturn(List.of(contentBlock));
        when(messages.create(any(MessageCreateParams.class))).thenReturn(message);

        scoringService.scoreAllPending();

        ArgumentCaptor<JobOffer> captor = ArgumentCaptor.forClass(JobOffer.class);
        verify(jobOfferRepository).save(captor.capture());
        assertThat(captor.getValue().getScore()).isEqualTo(OfferScore.STRONG);
        assertThat(captor.getValue().getScoreReason()).isEqualTo("Perfect match");
    }

    @Test
    void doesNothingWhenNoPendingOffers() {
        when(jobOfferRepository.findByScore(OfferScore.PENDING_SCORE)).thenReturn(List.of());
        scoringService.scoreAllPending();
        verify(anthropicClient, never()).messages();
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw_career_agent.service.ScoringServiceTest"
```

Expected: FAIL

- [ ] **Step 4: Implement `ScoringService.java`**

```java
package com.piotrcapecki.openclaw_career_agent.service;

import com.anthropic.client.Anthropic;
import com.anthropic.models.messages.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piotrcapecki.openclaw_career_agent.domain.*;
import com.piotrcapecki.openclaw_career_agent.dto.ScoreResultDto;
import com.piotrcapecki.openclaw_career_agent.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScoringService {

    private final Anthropic anthropicClient;
    private final JobOfferRepository jobOfferRepository;
    private final UserProfileRepository userProfileRepository;
    private final ObjectMapper objectMapper;

    public void scoreAllPending() {
        List<JobOffer> pending = jobOfferRepository.findByScore(OfferScore.PENDING_SCORE);
        if (pending.isEmpty()) {
            log.info("No pending offers to score");
            return;
        }

        UserProfile profile = userProfileRepository.findFirstByOrderByIdAsc()
                .orElseThrow(() -> new IllegalStateException("No user profile found. Set up your profile first via PATCH /api/profile"));

        String prompt = buildPrompt(profile, pending);

        try {
            Message response = anthropicClient.messages().create(
                    MessageCreateParams.builder()
                            .model(Model.CLAUDE_SONNET_4_5)
                            .maxTokens(4096)
                            .addUserMessageOfString(prompt)
                            .build()
            );

            String content = response.content().get(0).text()
                    .orElseThrow(() -> new RuntimeException("Empty response from Claude"))
                    .text();

            String jsonArray = extractJsonArray(content);
            List<ScoreResultDto> results = objectMapper.readValue(
                    jsonArray, new TypeReference<>() {});

            Map<String, JobOffer> offerMap = pending.stream()
                    .collect(Collectors.toMap(o -> o.getId().toString(), o -> o));

            for (ScoreResultDto result : results) {
                JobOffer offer = offerMap.get(result.offerId());
                if (offer == null) continue;
                try {
                    offer.setScore(OfferScore.valueOf(result.score()));
                    offer.setScoreReason(result.reason());
                    jobOfferRepository.save(offer);
                } catch (IllegalArgumentException e) {
                    log.warn("Unknown score value '{}' for offer {}", result.score(), result.offerId());
                }
            }
        } catch (Exception e) {
            log.error("Claude scoring failed — offers remain PENDING_SCORE: {}", e.getMessage());
        }
    }

    private String buildPrompt(UserProfile profile, List<JobOffer> offers) {
        try {
            List<Map<String, String>> offerList = offers.stream().map(o -> Map.of(
                    "offerId", o.getId().toString(),
                    "title", nvl(o.getTitle()),
                    "company", nvl(o.getCompany()),
                    "location", nvl(o.getLocation()),
                    "description", nvl(o.getDescription())
            )).toList();

            return """
                You are evaluating job offers for a candidate with the following profile:
                - Stack: %s
                - Level: %s
                - Locations: %s
                - Preferences: %s

                For each offer below, return a JSON array. Each element must have:
                  "offerId" (string), "score" (STRONG | MEDIUM | SKIP), "reason" (string, max 100 chars, in Polish)

                Return ONLY the JSON array, no markdown, no explanation.

                Offers:
                %s
                """.formatted(
                    String.join(", ", profile.getStack()),
                    String.join(", ", profile.getLevel()),
                    String.join(", ", profile.getLocations()),
                    profile.getPreferences(),
                    objectMapper.writeValueAsString(offerList)
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to build prompt", e);
        }
    }

    private String extractJsonArray(String content) {
        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');
        if (start == -1 || end == -1) throw new RuntimeException("No JSON array in Claude response");
        return content.substring(start, end + 1);
    }

    private String nvl(String value) {
        return value != null ? value : "";
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw_career_agent.service.ScoringServiceTest"
```

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/piotrcapecki/openclaw_career_agent/service/ScoringService.java \
  src/main/java/com/piotrcapecki/openclaw_career_agent/dto/ScoreResultDto.java \
  src/test/java/com/piotrcapecki/openclaw_career_agent/service/ScoringServiceTest.java
git commit -m "feat: implement ScoringService with Claude batch scoring"
```

---

## Task 13: Service — TelegramNotifier

**Files:**
- Create: `src/main/java/com/piotrcapecki/openclaw_career_agent/service/TelegramNotifier.java`
- Create: `src/test/java/com/piotrcapecki/openclaw_career_agent/service/TelegramNotifierTest.java`

Uses Java's built-in `java.net.http.HttpClient` to POST to the Telegram Bot API.

- [ ] **Step 1: Write failing tests**

```java
package com.piotrcapecki.openclaw_career_agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piotrcapecki.openclaw_career_agent.domain.*;
import com.piotrcapecki.openclaw_career_agent.repository.JobOfferRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelegramNotifierTest {

    @Mock JobOfferRepository jobOfferRepository;
    @InjectMocks TelegramNotifier notifier;

    @BeforeEach
    void setUp() {
        notifier = new TelegramNotifier(jobOfferRepository, new ObjectMapper(), "test-token", "123456");
    }

    @Test
    void doesNotSendWhenNoUnsentOffers() {
        when(jobOfferRepository.findBySentAtIsNullAndScore(OfferScore.STRONG)).thenReturn(List.of());
        when(jobOfferRepository.findBySentAtIsNullAndScore(OfferScore.MEDIUM)).thenReturn(List.of());

        // Should not throw and should not call the Telegram API
        // TelegramNotifier will bail early when both lists are empty
        notifier.sendDailyDigest();

        verify(jobOfferRepository, never()).save(any());
    }

    @Test
    void buildsDigestMessageCorrectly() {
        JobOffer offer = JobOffer.builder()
                .id(UUID.randomUUID())
                .title("Junior Java Developer")
                .company("Nordea")
                .location("Gdańsk")
                .url("https://example.com/job/1")
                .score(OfferScore.STRONG)
                .scoreReason("Perfect match")
                .build();

        String message = notifier.buildDigestMessage(List.of(offer), List.of());

        org.assertj.core.api.Assertions.assertThat(message)
                .contains("💚")
                .contains("Junior Java Developer")
                .contains("Nordea")
                .contains("https://example.com/job/1");
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw_career_agent.service.TelegramNotifierTest"
```

Expected: FAIL

- [ ] **Step 3: Implement `TelegramNotifier.java`**

```java
package com.piotrcapecki.openclaw_career_agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piotrcapecki.openclaw_career_agent.domain.*;
import com.piotrcapecki.openclaw_career_agent.repository.JobOfferRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class TelegramNotifier {

    private final JobOfferRepository jobOfferRepository;
    private final ObjectMapper objectMapper;
    private final String botToken;
    private final String chatId;

    public TelegramNotifier(
            JobOfferRepository jobOfferRepository,
            ObjectMapper objectMapper,
            @Value("${telegram.bot-token}") String botToken,
            @Value("${telegram.chat-id}") String chatId) {
        this.jobOfferRepository = jobOfferRepository;
        this.objectMapper = objectMapper;
        this.botToken = botToken;
        this.chatId = chatId;
    }

    public void sendDailyDigest() {
        List<JobOffer> strong = jobOfferRepository.findBySentAtIsNullAndScore(OfferScore.STRONG);
        List<JobOffer> medium = jobOfferRepository.findBySentAtIsNullAndScore(OfferScore.MEDIUM);

        if (strong.isEmpty() && medium.isEmpty()) {
            log.info("No unsent offers to report today");
            return;
        }

        String message = buildDigestMessage(strong, medium);

        try {
            sendTelegramMessage(message);
            LocalDateTime now = LocalDateTime.now();
            strong.forEach(o -> { o.setSentAt(now); jobOfferRepository.save(o); });
            medium.forEach(o -> { o.setSentAt(now); jobOfferRepository.save(o); });
            log.info("Telegram digest sent: {} strong, {} medium", strong.size(), medium.size());
        } catch (Exception e) {
            log.error("Telegram send failed — sent_at not updated, will retry tomorrow: {}", e.getMessage());
            throw new RuntimeException("Telegram send failed", e);
        }
    }

    String buildDigestMessage(List<JobOffer> strong, List<JobOffer> medium) {
        StringBuilder sb = new StringBuilder();
        sb.append("🔍 <b>OpenClaw — Daily Job Report</b>\n\n");

        if (!strong.isEmpty()) {
            sb.append("💚 <b>Mocne dopasowania (").append(strong.size()).append(")</b>\n");
            for (JobOffer o : strong) {
                sb.append("• ").append(escapeHtml(o.getTitle()))
                  .append(" @ ").append(escapeHtml(o.getCompany()))
                  .append(" — ").append(escapeHtml(o.getLocation())).append("\n")
                  .append("  <a href=\"").append(o.getUrl()).append("\">Zobacz ofertę</a>\n\n");
            }
        }

        if (!medium.isEmpty()) {
            sb.append("🟡 <b>Średnie dopasowania (").append(medium.size()).append(")</b>\n");
            for (JobOffer o : medium) {
                sb.append("• ").append(escapeHtml(o.getTitle()))
                  .append(" @ ").append(escapeHtml(o.getCompany()))
                  .append(" — ").append(escapeHtml(o.getLocation())).append("\n")
                  .append("  <a href=\"").append(o.getUrl()).append("\">Zobacz ofertę</a>\n\n");
            }
        }

        return sb.toString();
    }

    private void sendTelegramMessage(String text) throws Exception {
        String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
        String body = objectMapper.writeValueAsString(Map.of(
                "chat_id", chatId,
                "text", text,
                "parse_mode", "HTML",
                "disable_web_page_preview", true
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Telegram API error: " + response.statusCode() + " — " + response.body());
        }
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw_career_agent.service.TelegramNotifierTest"
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/piotrcapecki/openclaw_career_agent/service/TelegramNotifier.java \
  src/test/java/com/piotrcapecki/openclaw_career_agent/service/TelegramNotifierTest.java
git commit -m "feat: implement TelegramNotifier with HTML digest"
```

---

## Task 14: Scheduler & ScrapeController

**Files:**
- Create: `src/main/java/com/piotrcapecki/openclaw_career_agent/scheduler/DailyJobScheduler.java`
- Create: `src/main/java/com/piotrcapecki/openclaw_career_agent/dto/ScrapeRunDto.java`
- Create: `src/main/java/com/piotrcapecki/openclaw_career_agent/api/ScrapeController.java`
- Create: `src/test/java/com/piotrcapecki/openclaw_career_agent/api/ScrapeControllerTest.java`

- [ ] **Step 1: Create `DailyJobScheduler.java`**

```java
package com.piotrcapecki.openclaw_career_agent.scheduler;

import com.piotrcapecki.openclaw_career_agent.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DailyJobScheduler {

    private final JobIngestionService jobIngestionService;
    private final ScoringService scoringService;
    private final TelegramNotifier telegramNotifier;

    @Scheduled(cron = "0 0 8 * * *")
    public void runDailyPipeline() {
        log.info("Starting daily job pipeline...");
        try {
            jobIngestionService.ingest();
            scoringService.scoreAllPending();
            telegramNotifier.sendDailyDigest();
            log.info("Daily job pipeline complete");
        } catch (Exception e) {
            log.error("Daily pipeline error: {}", e.getMessage());
        }
    }
}
```

- [ ] **Step 2: Create `ScrapeRunDto.java`**

```java
package com.piotrcapecki.openclaw_career_agent.dto;

import com.piotrcapecki.openclaw_career_agent.domain.ScrapeRun;
import java.time.LocalDateTime;
import java.util.UUID;

public record ScrapeRunDto(
        UUID id,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        Integer newOffersCount,
        String status
) {
    public static ScrapeRunDto from(ScrapeRun run) {
        return new ScrapeRunDto(
                run.getId(), run.getStartedAt(), run.getFinishedAt(),
                run.getNewOffersCount(), run.getStatus()
        );
    }
}
```

- [ ] **Step 3: Write failing controller test**

```java
package com.piotrcapecki.openclaw_career_agent.api;

import com.piotrcapecki.openclaw_career_agent.domain.ScrapeRun;
import com.piotrcapecki.openclaw_career_agent.repository.ScrapeRunRepository;
import com.piotrcapecki.openclaw_career_agent.scheduler.DailyJobScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ScrapeController.class)
class ScrapeControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean DailyJobScheduler scheduler;
    @MockitoBean ScrapeRunRepository scrapeRunRepository;

    @Test
    void postScrapeRunTriggersPipeline() throws Exception {
        mockMvc.perform(post("/api/scrape/run")
                        .header("X-API-Key", "changeme"))
                .andExpect(status().isOk());

        verify(scheduler, times(1)).runDailyPipeline();
    }

    @Test
    void getScrapeRunsReturnsHistory() throws Exception {
        ScrapeRun run = ScrapeRun.builder()
                .id(UUID.randomUUID())
                .startedAt(LocalDateTime.now())
                .finishedAt(LocalDateTime.now())
                .newOffersCount(5)
                .status("SUCCESS")
                .build();

        when(scrapeRunRepository.findAllByOrderByStartedAtDesc()).thenReturn(List.of(run));

        mockMvc.perform(get("/api/scrape/runs")
                        .header("X-API-Key", "changeme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$[0].newOffersCount").value(5));
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw_career_agent.api.ScrapeControllerTest"
```

Expected: FAIL

- [ ] **Step 5: Implement `ScrapeController.java`**

```java
package com.piotrcapecki.openclaw_career_agent.api;

import com.piotrcapecki.openclaw_career_agent.dto.ScrapeRunDto;
import com.piotrcapecki.openclaw_career_agent.repository.ScrapeRunRepository;
import com.piotrcapecki.openclaw_career_agent.scheduler.DailyJobScheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/scrape")
@RequiredArgsConstructor
public class ScrapeController {

    private final DailyJobScheduler dailyJobScheduler;
    private final ScrapeRunRepository scrapeRunRepository;

    @PostMapping("/run")
    public ResponseEntity<Void> triggerScrape() {
        dailyJobScheduler.runDailyPipeline();
        return ResponseEntity.ok().build();
    }

    @GetMapping("/runs")
    public ResponseEntity<List<ScrapeRunDto>> getScrapeRuns() {
        List<ScrapeRunDto> runs = scrapeRunRepository.findAllByOrderByStartedAtDesc()
                .stream().map(ScrapeRunDto::from).toList();
        return ResponseEntity.ok(runs);
    }
}
```

- [ ] **Step 6: Run test to verify it passes**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw_career_agent.api.ScrapeControllerTest"
```

Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/piotrcapecki/openclaw_career_agent/scheduler/ \
  src/main/java/com/piotrcapecki/openclaw_career_agent/api/ScrapeController.java \
  src/main/java/com/piotrcapecki/openclaw_career_agent/dto/ScrapeRunDto.java \
  src/test/java/com/piotrcapecki/openclaw_career_agent/api/ScrapeControllerTest.java
git commit -m "feat: add DailyJobScheduler and ScrapeController"
```

---

## Task 15: Security — API Key Filter

**Files:**
- Create: `src/main/java/com/piotrcapecki/openclaw_career_agent/config/SecurityConfig.java`
- Create: `src/test/java/com/piotrcapecki/openclaw_career_agent/config/SecurityConfigTest.java`

All `/api/**` endpoints require an `X-API-Key` header matching `app.api-key` from `application.yaml`.

- [ ] **Step 1: Write failing security test**

```java
package com.piotrcapecki.openclaw_career_agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigTest {

    @Autowired MockMvc mockMvc;

    @Test
    void rejectsRequestWithoutApiKey() throws Exception {
        mockMvc.perform(get("/api/scrape/runs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void allowsRequestWithValidApiKey() throws Exception {
        mockMvc.perform(get("/api/scrape/runs")
                        .header("X-API-Key", "changeme"))
                .andExpect(status().isOk());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw_career_agent.config.SecurityConfigTest"
```

Expected: FAIL (no security configured yet, requests pass through)

- [ ] **Step 3: Implement `SecurityConfig.java`**

```java
package com.piotrcapecki.openclaw_career_agent.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.*;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${app.api-key}")
    private String apiKey;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll()
            )
            .addFilterBefore(apiKeyFilter(), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public OncePerRequestFilter apiKeyFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(
                    HttpServletRequest request,
                    HttpServletResponse response,
                    FilterChain filterChain) throws ServletException, IOException {

                String key = request.getHeader("X-API-Key");

                if (apiKey.equals(key)) {
                    var auth = new org.springframework.security.authentication
                            .UsernamePasswordAuthenticationToken("api", null, List.of());
                    org.springframework.security.core.context.SecurityContextHolder
                            .getContext().setAuthentication(auth);
                    filterChain.doFilter(request, response);
                } else {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.getWriter().write("{\"error\":\"Invalid or missing X-API-Key\"}");
                }
            }
        };
    }
}
```

Add the missing import at the top:
```java
import java.util.List;
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw_career_agent.config.SecurityConfigTest"
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/piotrcapecki/openclaw_career_agent/config/SecurityConfig.java \
  src/test/java/com/piotrcapecki/openclaw_career_agent/config/SecurityConfigTest.java
git commit -m "feat: add API key authentication via X-API-Key header"
```

---

## Task 16: API — ProfileController

**Files:**
- Create: `src/main/java/com/piotrcapecki/openclaw_career_agent/dto/UserProfileDto.java`
- Create: `src/main/java/com/piotrcapecki/openclaw_career_agent/api/ProfileController.java`
- Create: `src/test/java/com/piotrcapecki/openclaw_career_agent/api/ProfileControllerTest.java`

- [ ] **Step 1: Create `UserProfileDto.java`**

```java
package com.piotrcapecki.openclaw_career_agent.dto;

import com.piotrcapecki.openclaw_career_agent.domain.UserProfile;
import java.util.List;

public record UserProfileDto(
        List<String> stack,
        List<String> level,
        List<String> locations,
        String preferences
) {
    public static UserProfileDto from(UserProfile profile) {
        return new UserProfileDto(
                profile.getStack(),
                profile.getLevel(),
                profile.getLocations(),
                profile.getPreferences()
        );
    }
}
```

- [ ] **Step 2: Write failing tests**

```java
package com.piotrcapecki.openclaw_career_agent.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piotrcapecki.openclaw_career_agent.domain.UserProfile;
import com.piotrcapecki.openclaw_career_agent.repository.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ProfileController.class)
class ProfileControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean UserProfileRepository userProfileRepository;

    @Test
    void getProfileReturns200WithProfile() throws Exception {
        UserProfile profile = UserProfile.builder()
                .id(1L)
                .stack(List.of("Java", "Spring Boot"))
                .level(List.of("junior"))
                .locations(List.of("Gdańsk", "remote"))
                .preferences("Backend only")
                .build();

        when(userProfileRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(profile));

        mockMvc.perform(get("/api/profile").header("X-API-Key", "changeme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stack[0]").value("Java"))
                .andExpect(jsonPath("$.level[0]").value("junior"));
    }

    @Test
    void getProfileReturns404WhenNoProfile() throws Exception {
        when(userProfileRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/profile").header("X-API-Key", "changeme"))
                .andExpect(status().isNotFound());
    }

    @Test
    void patchProfileUpdatesAndReturns200() throws Exception {
        String body = objectMapper.writeValueAsString(
                new java.util.HashMap<>(java.util.Map.of(
                        "stack", List.of("Java", "Spring"),
                        "level", List.of("junior"),
                        "locations", List.of("Gdańsk"),
                        "preferences", "Backend REST"
                ))
        );

        when(userProfileRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());
        when(userProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(patch("/api/profile")
                        .header("X-API-Key", "changeme")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferences").value("Backend REST"));
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw_career_agent.api.ProfileControllerTest"
```

Expected: FAIL

- [ ] **Step 4: Implement `ProfileController.java`**

```java
package com.piotrcapecki.openclaw_career_agent.api;

import com.piotrcapecki.openclaw_career_agent.domain.UserProfile;
import com.piotrcapecki.openclaw_career_agent.dto.UserProfileDto;
import com.piotrcapecki.openclaw_career_agent.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final UserProfileRepository userProfileRepository;

    @GetMapping
    public ResponseEntity<UserProfileDto> getProfile() {
        return userProfileRepository.findFirstByOrderByIdAsc()
                .map(UserProfileDto::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PatchMapping
    public ResponseEntity<UserProfileDto> patchProfile(@RequestBody Map<String, Object> updates) {
        UserProfile profile = userProfileRepository.findFirstByOrderByIdAsc()
                .orElse(UserProfile.builder().build());

        if (updates.containsKey("stack"))
            profile.setStack(castToStringList(updates.get("stack")));
        if (updates.containsKey("level"))
            profile.setLevel(castToStringList(updates.get("level")));
        if (updates.containsKey("locations"))
            profile.setLocations(castToStringList(updates.get("locations")));
        if (updates.containsKey("preferences"))
            profile.setPreferences((String) updates.get("preferences"));

        profile.setUpdatedAt(LocalDateTime.now());
        UserProfile saved = userProfileRepository.save(profile);
        return ResponseEntity.ok(UserProfileDto.from(saved));
    }

    @SuppressWarnings("unchecked")
    private List<String> castToStringList(Object value) {
        return (List<String>) value;
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw_career_agent.api.ProfileControllerTest"
```

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/piotrcapecki/openclaw_career_agent/api/ProfileController.java \
  src/main/java/com/piotrcapecki/openclaw_career_agent/dto/UserProfileDto.java \
  src/test/java/com/piotrcapecki/openclaw_career_agent/api/ProfileControllerTest.java
git commit -m "feat: add ProfileController with GET and PATCH endpoints"
```

---

## Task 17: API — OffersController

**Files:**
- Create: `src/main/java/com/piotrcapecki/openclaw_career_agent/dto/JobOfferDto.java`
- Create: `src/main/java/com/piotrcapecki/openclaw_career_agent/api/OffersController.java`
- Create: `src/test/java/com/piotrcapecki/openclaw_career_agent/api/OffersControllerTest.java`

- [ ] **Step 1: Create `JobOfferDto.java`**

```java
package com.piotrcapecki.openclaw_career_agent.dto;

import com.piotrcapecki.openclaw_career_agent.domain.JobOffer;
import java.time.LocalDateTime;
import java.util.UUID;

public record JobOfferDto(
        UUID id,
        String source,
        String title,
        String company,
        String location,
        String url,
        String score,
        String scoreReason,
        LocalDateTime foundAt,
        LocalDateTime sentAt
) {
    public static JobOfferDto from(JobOffer offer) {
        return new JobOfferDto(
                offer.getId(),
                offer.getSource() != null ? offer.getSource().name() : null,
                offer.getTitle(),
                offer.getCompany(),
                offer.getLocation(),
                offer.getUrl(),
                offer.getScore() != null ? offer.getScore().name() : null,
                offer.getScoreReason(),
                offer.getFoundAt(),
                offer.getSentAt()
        );
    }
}
```

- [ ] **Step 2: Write failing tests**

```java
package com.piotrcapecki.openclaw_career_agent.api;

import com.piotrcapecki.openclaw_career_agent.domain.*;
import com.piotrcapecki.openclaw_career_agent.repository.JobOfferRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OffersController.class)
class OffersControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JobOfferRepository jobOfferRepository;

    @Test
    void getOffersReturnsAllOffers() throws Exception {
        JobOffer offer = JobOffer.builder()
                .id(UUID.randomUUID())
                .title("Junior Java Dev")
                .company("Nordea")
                .location("Gdańsk")
                .source(JobSource.JUSTJOINIT)
                .score(OfferScore.STRONG)
                .build();

        when(jobOfferRepository.findAllByOrderByFoundAtDesc()).thenReturn(List.of(offer));

        mockMvc.perform(get("/api/offers").header("X-API-Key", "changeme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Junior Java Dev"))
                .andExpect(jsonPath("$[0].score").value("STRONG"));
    }

    @Test
    void getOfferByIdReturns404WhenNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(jobOfferRepository.findById(id)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/offers/" + id).header("X-API-Key", "changeme"))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw_career_agent.api.OffersControllerTest"
```

Expected: FAIL

- [ ] **Step 4: Implement `OffersController.java`**

```java
package com.piotrcapecki.openclaw_career_agent.api;

import com.piotrcapecki.openclaw_career_agent.dto.JobOfferDto;
import com.piotrcapecki.openclaw_career_agent.repository.JobOfferRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/offers")
@RequiredArgsConstructor
public class OffersController {

    private final JobOfferRepository jobOfferRepository;

    @GetMapping
    public ResponseEntity<List<JobOfferDto>> getOffers() {
        List<JobOfferDto> offers = jobOfferRepository.findAllByOrderByFoundAtDesc()
                .stream().map(JobOfferDto::from).toList();
        return ResponseEntity.ok(offers);
    }

    @GetMapping("/{id}")
    public ResponseEntity<JobOfferDto> getOffer(@PathVariable UUID id) {
        return jobOfferRepository.findById(id)
                .map(JobOfferDto::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw_career_agent.api.OffersControllerTest"
```

Expected: PASS

- [ ] **Step 6: Run full test suite**

```bash
./gradlew test
```

Expected: all tests PASS, `BUILD SUCCESSFUL`

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/piotrcapecki/openclaw_career_agent/api/OffersController.java \
  src/main/java/com/piotrcapecki/openclaw_career_agent/dto/JobOfferDto.java \
  src/test/java/com/piotrcapecki/openclaw_career_agent/api/OffersControllerTest.java
git commit -m "feat: add OffersController for listing and viewing job offers"
```

---

## Post-Implementation Checklist

- [ ] Set environment variables on the VPS: `DB_USERNAME`, `DB_PASSWORD`, `ANTHROPIC_API_KEY`, `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`, `APP_API_KEY`
- [ ] Create a Telegram bot via [@BotFather](https://t.me/BotFather) and get the token
- [ ] Send `/start` to your bot and get your `chat_id` via `https://api.telegram.org/bot<TOKEN>/getUpdates`
- [ ] Trigger a manual scrape via `POST /api/scrape/run` and verify offers appear in the database
- [ ] Verify Jsoup scrapers return results against live pages (JIT.team, Amazon, Deloitte selectors may need adjustment)
- [ ] Add your profile via `PATCH /api/profile` before the first scoring run
