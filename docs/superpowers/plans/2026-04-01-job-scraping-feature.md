# CareerAgent Skill — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the CareerAgent skill for the OpenClaw platform — daily job scraping, AI scoring via OpenRouter, and Telegram digest.

**Architecture:** The platform has a `core/` layer (OpenRouter AI client, Telegram client, Security) shared by all future skills. The CareerAgent lives entirely under `skill/career/`. Five `JobScraper` implementations feed `JobIngestionService`, which deduplicates against PostgreSQL. `CareerScoringService` batch-scores new offers via OpenRouter (Claude). `TelegramClient` sends one daily digest grouped by score.

**Tech Stack:** Java 21, Spring Boot 4, Spring Data JPA, Flyway, PostgreSQL, Jsoup 1.17.2, OkHttp 4.12.0, Lombok, JUnit 5, Mockito, AssertJ.

---

## File Map

```
src/main/java/com/piotrcapecki/openclaw/
├── OpenClawApplication.java                              (already created)
├── core/
│   ├── ai/
│   │   └── OpenRouterClient.java                        # HTTP client wrapping OpenRouter API
│   ├── notification/
│   │   └── TelegramClient.java                          # generic Telegram sender
│   └── config/
│       ├── CoreConfig.java                              # OkHttpClient bean
│       └── SecurityConfig.java                         # X-API-Key filter
└── skill/
    └── career/
        ├── domain/
        │   ├── JobOffer.java
        │   ├── UserProfile.java
        │   ├── ScrapeRun.java
        │   ├── JobSource.java                           (enum)
        │   ├── OfferScore.java                          (enum)
        │   └── StringListConverter.java
        ├── repository/
        │   ├── JobOfferRepository.java
        │   ├── UserProfileRepository.java
        │   └── ScrapeRunRepository.java
        ├── scraper/
        │   ├── JobScraper.java                          (interface)
        │   ├── RawJobOffer.java                         (record)
        │   ├── JustJoinItScraper.java
        │   ├── NoFluffJobsScraper.java
        │   ├── JitTeamScraper.java
        │   ├── AmazonJobsScraper.java
        │   └── DeloitteJobsScraper.java
        ├── service/
        │   ├── JobIngestionService.java
        │   └── CareerScoringService.java
        ├── scheduler/
        │   └── CareerScheduler.java
        ├── api/
        │   ├── ProfileController.java
        │   ├── OffersController.java
        │   └── ScrapeController.java
        └── dto/
            ├── UserProfileDto.java
            ├── JobOfferDto.java
            ├── ScrapeRunDto.java
            └── ScoreResultDto.java                      (internal, Claude response parsing)

src/main/resources/
├── application.yaml
└── db/migration/
    ├── V1__create_user_profile.sql
    ├── V2__create_job_offers.sql
    └── V3__create_scrape_runs.sql

src/test/java/com/piotrcapecki/openclaw/
├── OpenClawApplicationTests.java                        (already created)
├── core/
│   ├── ai/
│   │   └── OpenRouterClientTest.java
│   └── notification/
│       └── TelegramClientTest.java
└── skill/career/
    ├── domain/
    │   └── StringListConverterTest.java
    ├── scraper/
    │   ├── JustJoinItScraperTest.java
    │   ├── NoFluffJobsScraperTest.java
    │   ├── JitTeamScraperTest.java
    │   ├── AmazonJobsScraperTest.java
    │   └── DeloitteJobsScraperTest.java
    ├── service/
    │   ├── JobIngestionServiceTest.java
    │   └── CareerScoringServiceTest.java
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
- Create: `src/main/java/com/piotrcapecki/openclaw/core/config/CoreConfig.java`

- [ ] **Step 1: Update `build.gradle.kts` dependencies**

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

- [ ] **Step 2: Populate `application.yaml`**

```yaml
spring:
  application:
    name: OpenClaw
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

openrouter:
  api-key: ${OPENROUTER_API_KEY}
  model: ${OPENROUTER_MODEL:anthropic/claude-sonnet-4-5}
  base-url: https://openrouter.ai/api/v1

telegram:
  bot-token: ${TELEGRAM_BOT_TOKEN}
  chat-id: ${TELEGRAM_CHAT_ID}

app:
  api-key: ${APP_API_KEY:changeme}
```

- [ ] **Step 3: Create `CoreConfig.java`**

```java
package com.piotrcapecki.openclaw.core.config;

import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.concurrent.TimeUnit;

@Configuration
public class CoreConfig {

    @Bean
    public OkHttpClient okHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
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
  src/main/java/com/piotrcapecki/openclaw/core/config/CoreConfig.java
git commit -m "chore: add dependencies and base platform configuration"
```

---

## Task 2: Core — OpenRouterClient

**Files:**
- Create: `src/main/java/com/piotrcapecki/openclaw/core/ai/OpenRouterClient.java`
- Create: `src/test/java/com/piotrcapecki/openclaw/core/ai/OpenRouterClientTest.java`

This is the single AI gateway for the entire platform. All skills call this — `CareerScoringService` today, `PersonalOps` tomorrow.

- [ ] **Step 1: Write failing test**

```java
package com.piotrcapecki.openclaw.core.ai;

import okhttp3.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OpenRouterClientTest {

    @Mock OkHttpClient httpClient;

    @Test
    void returnsResponseTextFromOpenRouter() throws Exception {
        String responseJson = """
            {
              "choices": [{
                "message": { "content": "Hello from Claude" }
              }]
            }
            """;

        Call call = mock(Call.class);
        Response response = new Response.Builder()
                .request(new Request.Builder().url("https://openrouter.ai/api/v1/chat/completions").build())
                .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ResponseBody.create(responseJson, MediaType.parse("application/json")))
                .build();

        when(httpClient.newCall(any())).thenReturn(call);
        when(call.execute()).thenReturn(response);

        OpenRouterClient client = new OpenRouterClient(httpClient, "test-key", "anthropic/claude-sonnet-4-5");
        String result = client.complete("Say hello");

        assertThat(result).isEqualTo("Hello from Claude");
    }

    @Test
    void throwsWhenApiReturnsError() throws Exception {
        Call call = mock(Call.class);
        Response response = new Response.Builder()
                .request(new Request.Builder().url("https://openrouter.ai/api/v1/chat/completions").build())
                .protocol(Protocol.HTTP_1_1).code(429).message("Too Many Requests")
                .body(ResponseBody.create("{}", MediaType.parse("application/json")))
                .build();

        when(httpClient.newCall(any())).thenReturn(call);
        when(call.execute()).thenReturn(response);

        OpenRouterClient client = new OpenRouterClient(httpClient, "test-key", "anthropic/claude-sonnet-4-5");

        org.junit.jupiter.api.Assertions.assertThrows(
                RuntimeException.class,
                () -> client.complete("Say hello")
        );
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw.core.ai.OpenRouterClientTest"
```

Expected: FAIL — `OpenRouterClient` does not exist.

- [ ] **Step 3: Implement `OpenRouterClient.java`**

```java
package com.piotrcapecki.openclaw.core.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@Slf4j
public class OpenRouterClient {

    private static final String API_URL = "https://openrouter.ai/api/v1/chat/completions";
    private static final MediaType JSON = MediaType.parse("application/json");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;

    public OpenRouterClient(
            OkHttpClient httpClient,
            @Value("${openrouter.api-key}") String apiKey,
            @Value("${openrouter.model}") String model) {
        this.httpClient = httpClient;
        this.objectMapper = new ObjectMapper();
        this.apiKey = apiKey;
        this.model = model;
    }

    /**
     * Sends a single user prompt and returns the model's text response.
     * Used by all skills on the platform.
     */
    public String complete(String userPrompt) {
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "model", model,
                    "messages", List.of(Map.of("role", "user", "content", userPrompt))
            ));

            Request request = new Request.Builder()
                    .url(API_URL)
                    .post(RequestBody.create(body, JSON))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    throw new RuntimeException("OpenRouter error: HTTP " + response.code());
                }
                JsonNode root = objectMapper.readTree(response.body().string());
                return root.path("choices").get(0).path("message").path("content").asText();
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("OpenRouter call failed: " + e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw.core.ai.OpenRouterClientTest"
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/piotrcapecki/openclaw/core/ai/OpenRouterClient.java \
  src/test/java/com/piotrcapecki/openclaw/core/ai/OpenRouterClientTest.java
git commit -m "feat(core): add OpenRouterClient — shared AI gateway for all skills"
```

---

## Task 3: Core — TelegramClient

**Files:**
- Create: `src/main/java/com/piotrcapecki/openclaw/core/notification/TelegramClient.java`
- Create: `src/test/java/com/piotrcapecki/openclaw/core/notification/TelegramClientTest.java`

- [ ] **Step 1: Write failing test**

```java
package com.piotrcapecki.openclaw.core.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TelegramClientTest {

    @Test
    void buildMessageTextFormatsCorrectly() {
        TelegramClient client = new TelegramClient(
                new ObjectMapper(), "fake-token", "123456"
        );

        String text = client.formatMessage("*Title*", "Body line 1\nBody line 2");

        assertThat(text).contains("Title").contains("Body line 1");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw.core.notification.TelegramClientTest"
```

Expected: FAIL

- [ ] **Step 3: Implement `TelegramClient.java`**

```java
package com.piotrcapecki.openclaw.core.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.*;
import java.util.Map;

@Component
@Slf4j
public class TelegramClient {

    private final ObjectMapper objectMapper;
    private final String botToken;
    private final String chatId;

    public TelegramClient(
            ObjectMapper objectMapper,
            @Value("${telegram.bot-token}") String botToken,
            @Value("${telegram.chat-id}") String chatId) {
        this.objectMapper = objectMapper;
        this.botToken = botToken;
        this.chatId = chatId;
    }

    /**
     * Sends an HTML-formatted message to the configured Telegram chat.
     * Used by all skills on the platform.
     */
    public void send(String htmlMessage) {
        try {
            String url = "https://api.telegram.org/bot" + botToken + "/sendMessage";
            String body = objectMapper.writeValueAsString(Map.of(
                    "chat_id", chatId,
                    "text", htmlMessage,
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
                throw new RuntimeException(
                        "Telegram API error: " + response.statusCode() + " — " + response.body());
            }
            log.info("Telegram message sent successfully");
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Telegram send failed: " + e.getMessage(), e);
        }
    }

    /**
     * Helper to build a titled message block. Skills use this to compose their messages.
     */
    public String formatMessage(String title, String body) {
        return "<b>" + escapeHtml(title) + "</b>\n\n" + body;
    }

    public String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw.core.notification.TelegramClientTest"
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/piotrcapecki/openclaw/core/notification/TelegramClient.java \
  src/test/java/com/piotrcapecki/openclaw/core/notification/TelegramClientTest.java
git commit -m "feat(core): add TelegramClient — shared notification gateway for all skills"
```

---

## Task 4: Core — Security

**Files:**
- Create: `src/main/java/com/piotrcapecki/openclaw/core/config/SecurityConfig.java`
- Create: `src/test/java/com/piotrcapecki/openclaw/core/config/SecurityConfigTest.java`

All `/api/**` endpoints require an `X-API-Key` header matching `app.api-key` from `application.yaml`.

- [ ] **Step 1: Write failing security test**

```java
package com.piotrcapecki.openclaw.core.config;

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
        mockMvc.perform(get("/api/career/scrape/runs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void allowsRequestWithValidApiKey() throws Exception {
        mockMvc.perform(get("/api/career/scrape/runs")
                        .header("X-API-Key", "changeme"))
                .andExpect(status().isOk());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw.core.config.SecurityConfigTest"
```

Expected: FAIL

- [ ] **Step 3: Implement `SecurityConfig.java`**

```java
package com.piotrcapecki.openclaw.core.config;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

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
                    var auth = new UsernamePasswordAuthenticationToken(
                            "api", null, List.of());
                    SecurityContextHolder.getContext().setAuthentication(auth);
                    filterChain.doFilter(request, response);
                } else {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"Invalid or missing X-API-Key\"}");
                }
            }
        };
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw.core.config.SecurityConfigTest"
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/piotrcapecki/openclaw/core/config/SecurityConfig.java \
  src/test/java/com/piotrcapecki/openclaw/core/config/SecurityConfigTest.java
git commit -m "feat(core): add API key security filter"
```

---

## Task 5: Database — Flyway Migrations

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

CREATE INDEX idx_job_offers_score   ON job_offers(score);
CREATE INDEX idx_job_offers_sent_at ON job_offers(sent_at);
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

Start a local PostgreSQL instance, create the `openclaw` database, then:

```bash
./gradlew bootRun
```

Expected: Flyway applies V1, V2, V3 — `Successfully applied 3 migrations`. Stop with `Ctrl+C`.

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/
git commit -m "feat(career): add Flyway migrations for user_profile, job_offers, scrape_runs"
```

---

## Task 6: CareerAgent — Domain Model

**Files:**
- Create: `src/main/java/com/piotrcapecki/openclaw/skill/career/domain/JobSource.java`
- Create: `src/main/java/com/piotrcapecki/openclaw/skill/career/domain/OfferScore.java`
- Create: `src/main/java/com/piotrcapecki/openclaw/skill/career/domain/StringListConverter.java`
- Create: `src/main/java/com/piotrcapecki/openclaw/skill/career/domain/UserProfile.java`
- Create: `src/main/java/com/piotrcapecki/openclaw/skill/career/domain/JobOffer.java`
- Create: `src/main/java/com/piotrcapecki/openclaw/skill/career/domain/ScrapeRun.java`
- Create: `src/test/java/com/piotrcapecki/openclaw/skill/career/domain/StringListConverterTest.java`

- [ ] **Step 1: Write failing test for `StringListConverter`**

```java
package com.piotrcapecki.openclaw.skill.career.domain;

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

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw.skill.career.domain.StringListConverterTest"
```

Expected: FAIL

- [ ] **Step 3: Create enums**

`JobSource.java`:
```java
package com.piotrcapecki.openclaw.skill.career.domain;

public enum JobSource {
    JUSTJOINIT, NOFLUFFJOBS, JIT, AMAZON, DELOITTE
}
```

`OfferScore.java`:
```java
package com.piotrcapecki.openclaw.skill.career.domain;

public enum OfferScore {
    PENDING_SCORE, STRONG, MEDIUM, SKIP
}
```

- [ ] **Step 4: Create `StringListConverter.java`**

```java
package com.piotrcapecki.openclaw.skill.career.domain;

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

- [ ] **Step 5: Run test to verify it passes**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw.skill.career.domain.StringListConverterTest"
```

Expected: PASS

- [ ] **Step 6: Create `UserProfile.java`**

```java
package com.piotrcapecki.openclaw.skill.career.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "user_profile")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
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
package com.piotrcapecki.openclaw.skill.career.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "job_offers")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
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
package com.piotrcapecki.openclaw.skill.career.domain;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "scrape_runs")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
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
git add src/main/java/com/piotrcapecki/openclaw/skill/career/domain/ \
  src/test/java/com/piotrcapecki/openclaw/skill/career/domain/
git commit -m "feat(career): add domain entities, enums, and StringListConverter"
```

---

## Task 7: CareerAgent — Repositories

**Files:**
- Create: `src/main/java/com/piotrcapecki/openclaw/skill/career/repository/JobOfferRepository.java`
- Create: `src/main/java/com/piotrcapecki/openclaw/skill/career/repository/UserProfileRepository.java`
- Create: `src/main/java/com/piotrcapecki/openclaw/skill/career/repository/ScrapeRunRepository.java`

- [ ] **Step 1: Create `UserProfileRepository.java`**

```java
package com.piotrcapecki.openclaw.skill.career.repository;

import com.piotrcapecki.openclaw.skill.career.domain.UserProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {
    Optional<UserProfile> findFirstByOrderByIdAsc();
}
```

- [ ] **Step 2: Create `JobOfferRepository.java`**

```java
package com.piotrcapecki.openclaw.skill.career.repository;

import com.piotrcapecki.openclaw.skill.career.domain.JobOffer;
import com.piotrcapecki.openclaw.skill.career.domain.OfferScore;
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
package com.piotrcapecki.openclaw.skill.career.repository;

import com.piotrcapecki.openclaw.skill.career.domain.ScrapeRun;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface ScrapeRunRepository extends JpaRepository<ScrapeRun, UUID> {
    List<ScrapeRun> findAllByOrderByStartedAtDesc();
}
```

- [ ] **Step 4: Verify application context loads**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw.OpenClawApplicationTests"
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/piotrcapecki/openclaw/skill/career/repository/
git commit -m "feat(career): add JPA repositories"
```

---

## Task 8: CareerAgent — Scraper Interface & RawJobOffer

**Files:**
- Create: `src/main/java/com/piotrcapecki/openclaw/skill/career/scraper/RawJobOffer.java`
- Create: `src/main/java/com/piotrcapecki/openclaw/skill/career/scraper/JobScraper.java`
- Create: `src/test/java/com/piotrcapecki/openclaw/skill/career/scraper/RawJobOfferTest.java`

- [ ] **Step 1: Write failing test**

```java
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
        assertThat(offer.source()).isEqualTo(JobSource.JUSTJOINIT);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw.skill.career.scraper.RawJobOfferTest"
```

Expected: FAIL

- [ ] **Step 3: Create `RawJobOffer.java`**

```java
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
```

- [ ] **Step 4: Create `JobScraper.java`**

```java
package com.piotrcapecki.openclaw.skill.career.scraper;

import com.piotrcapecki.openclaw.skill.career.domain.JobSource;
import java.util.List;

public interface JobScraper {
    JobSource getSource();
    List<RawJobOffer> scrape();
}
```

- [ ] **Step 5: Run test to verify it passes**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw.skill.career.scraper.RawJobOfferTest"
```

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/piotrcapecki/openclaw/skill/career/scraper/RawJobOffer.java \
  src/main/java/com/piotrcapecki/openclaw/skill/career/scraper/JobScraper.java \
  src/test/java/com/piotrcapecki/openclaw/skill/career/scraper/RawJobOfferTest.java
git commit -m "feat(career): add JobScraper interface and RawJobOffer record"
```

---

## Task 9: CareerAgent — JobIngestionService

**Files:**
- Create: `src/main/java/com/piotrcapecki/openclaw/skill/career/service/JobIngestionService.java`
- Create: `src/test/java/com/piotrcapecki/openclaw/skill/career/service/JobIngestionServiceTest.java`

- [ ] **Step 1: Write failing tests**

```java
package com.piotrcapecki.openclaw.skill.career.service;

import com.piotrcapecki.openclaw.skill.career.domain.*;
import com.piotrcapecki.openclaw.skill.career.repository.*;
import com.piotrcapecki.openclaw.skill.career.scraper.*;
import org.junit.jupiter.api.*;
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
./gradlew test --tests "com.piotrcapecki.openclaw.skill.career.service.JobIngestionServiceTest"
```

Expected: FAIL

- [ ] **Step 3: Implement `JobIngestionService.java`**

```java
package com.piotrcapecki.openclaw.skill.career.service;

import com.piotrcapecki.openclaw.skill.career.domain.*;
import com.piotrcapecki.openclaw.skill.career.repository.*;
import com.piotrcapecki.openclaw.skill.career.scraper.*;
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
                        jobOfferRepository.save(JobOffer.builder()
                                .externalId(externalId)
                                .source(raw.source())
                                .title(raw.title())
                                .company(raw.company())
                                .location(raw.location())
                                .url(raw.url())
                                .description(raw.description())
                                .score(OfferScore.PENDING_SCORE)
                                .foundAt(LocalDateTime.now())
                                .build());
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
./gradlew test --tests "com.piotrcapecki.openclaw.skill.career.service.JobIngestionServiceTest"
```

Expected: all 3 PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/piotrcapecki/openclaw/skill/career/service/JobIngestionService.java \
  src/test/java/com/piotrcapecki/openclaw/skill/career/service/JobIngestionServiceTest.java
git commit -m "feat(career): implement JobIngestionService with deduplication"
```

---

## Task 10: CareerAgent — JustJoinIT Scraper

**Files:**
- Create: `src/main/java/com/piotrcapecki/openclaw/skill/career/scraper/JustJoinItScraper.java`
- Create: `src/test/java/com/piotrcapecki/openclaw/skill/career/scraper/JustJoinItScraperTest.java`

- [ ] **Step 1: Write failing test**

```java
package com.piotrcapecki.openclaw.skill.career.scraper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piotrcapecki.openclaw.skill.career.domain.JobSource;
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

    @Test
    void parsesJavaJuniorOffersAndFiltersOutOthers() throws Exception {
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
                .protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(ResponseBody.create(json, MediaType.parse("application/json")))
                .build();
        when(httpClient.newCall(any())).thenReturn(call);
        when(call.execute()).thenReturn(response);

        JustJoinItScraper scraper = new JustJoinItScraper(httpClient, new ObjectMapper());
        List<RawJobOffer> offers = scraper.scrape();

        assertThat(offers).hasSize(1);
        assertThat(offers.get(0).title()).isEqualTo("Junior Java Developer");
        assertThat(offers.get(0).source()).isEqualTo(JobSource.JUSTJOINIT);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw.skill.career.scraper.JustJoinItScraperTest"
```

Expected: FAIL

- [ ] **Step 3: Implement `JustJoinItScraper.java`**

```java
package com.piotrcapecki.openclaw.skill.career.scraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piotrcapecki.openclaw.skill.career.domain.JobSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class JustJoinItScraper implements JobScraper {

    private static final String API_URL = "https://justjoin.it/api/offers";
    private static final Set<String> ACCEPTED_CITIES = Set.of("gdańsk", "gdynia", "sopot");
    private static final Set<String> ACCEPTED_LEVELS = Set.of("junior", "intern", "internship");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Override
    public JobSource getSource() { return JobSource.JUSTJOINIT; }

    @Override
    public List<RawJobOffer> scrape() {
        Request request = new Request.Builder()
                .url(API_URL).header("Accept", "application/json").build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                log.warn("JustJoinIT API returned {}", response.code());
                return List.of();
            }

            JsonNode offers = objectMapper.readTree(response.body().string());
            List<RawJobOffer> result = new ArrayList<>();

            for (JsonNode offer : offers) {
                if (!isJava(offer) || !isJuniorOrIntern(offer)) continue;
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

    private boolean isJava(JsonNode o) {
        return o.path("marker_icon").asText().toLowerCase().contains("java")
                || o.path("title").asText().toLowerCase().contains("java");
    }

    private boolean isJuniorOrIntern(JsonNode o) {
        return ACCEPTED_LEVELS.contains(o.path("experience_level").asText().toLowerCase());
    }

    private boolean isTriCity(JsonNode o) {
        return ACCEPTED_CITIES.contains(o.path("city").asText().toLowerCase());
    }

    private boolean isRemote(JsonNode o) {
        return o.path("workplace_type").asText().toLowerCase().contains("remote");
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw.skill.career.scraper.JustJoinItScraperTest"
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/piotrcapecki/openclaw/skill/career/scraper/JustJoinItScraper.java \
  src/test/java/com/piotrcapecki/openclaw/skill/career/scraper/JustJoinItScraperTest.java
git commit -m "feat(career): implement JustJoinIT scraper"
```

---

## Task 11: CareerAgent — NoFluffJobs Scraper

**Files:**
- Create: `src/main/java/com/piotrcapecki/openclaw/skill/career/scraper/NoFluffJobsScraper.java`
- Create: `src/test/java/com/piotrcapecki/openclaw/skill/career/scraper/NoFluffJobsScraperTest.java`

- [ ] **Step 1: Write failing test**

```java
package com.piotrcapecki.openclaw.skill.career.scraper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piotrcapecki.openclaw.skill.career.domain.JobSource;
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
    void parsesJuniorJavaOffersFromResponse() throws Exception {
        String json = """
            {
              "postings": [{
                "id": "javadev-123",
                "name": "Java Developer",
                "company": { "name": "Accenture" },
                "location": { "places": [{ "city": "Gdańsk" }] },
                "seniority": ["junior"],
                "remote": false
              }]
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
        assertThat(offers.get(0).source()).isEqualTo(JobSource.NOFLUFFJOBS);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw.skill.career.scraper.NoFluffJobsScraperTest"
```

Expected: FAIL

- [ ] **Step 3: Implement `NoFluffJobsScraper.java`**

```java
package com.piotrcapecki.openclaw.skill.career.scraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piotrcapecki.openclaw.skill.career.domain.JobSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Component;

import java.util.*;

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
    public JobSource getSource() { return JobSource.NOFLUFFJOBS; }

    @Override
    public List<RawJobOffer> scrape() {
        try {
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "criteriaSearch", Map.of(
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
                List<RawJobOffer> result = new ArrayList<>();

                for (JsonNode posting : root.path("postings")) {
                    if (!isJuniorOrIntern(posting)) continue;
                    if (!isTriCityOrRemote(posting)) continue;

                    String id = posting.path("id").asText();
                    String title = posting.path("name").asText();
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
        for (JsonNode s : posting.path("seniority"))
            if (ACCEPTED_SENIORITY.contains(s.asText().toLowerCase())) return true;
        return false;
    }

    private boolean isTriCityOrRemote(JsonNode posting) {
        for (JsonNode place : posting.path("location").path("places"))
            if (ACCEPTED_CITIES.contains(place.path("city").asText("").toLowerCase())) return true;
        return posting.path("remote").asBoolean(false);
    }

    private String extractLocation(JsonNode posting) {
        JsonNode places = posting.path("location").path("places");
        return places.size() > 0 ? places.get(0).path("city").asText("N/A") : "remote";
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw.skill.career.scraper.NoFluffJobsScraperTest"
```

Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/piotrcapecki/openclaw/skill/career/scraper/NoFluffJobsScraper.java \
  src/test/java/com/piotrcapecki/openclaw/skill/career/scraper/NoFluffJobsScraperTest.java
git commit -m "feat(career): implement NoFluffJobs scraper"
```

---

## Task 12: CareerAgent — Jsoup Scrapers (JIT, Amazon, Deloitte)

**Files:**
- Create: `src/main/java/com/piotrcapecki/openclaw/skill/career/scraper/JitTeamScraper.java`
- Create: `src/main/java/com/piotrcapecki/openclaw/skill/career/scraper/AmazonJobsScraper.java`
- Create: `src/main/java/com/piotrcapecki/openclaw/skill/career/scraper/DeloitteJobsScraper.java`
- Create: `src/test/java/com/piotrcapecki/openclaw/skill/career/scraper/JitTeamScraperTest.java`
- Create: `src/test/java/com/piotrcapecki/openclaw/skill/career/scraper/AmazonJobsScraperTest.java`
- Create: `src/test/java/com/piotrcapecki/openclaw/skill/career/scraper/DeloitteJobsScraperTest.java`

> ⚠️ All three scrapers follow the same pattern: override `fetchDocument()` to inject static HTML in tests; use Jsoup in production. **Verify CSS selectors against live pages before first production run.**

- [ ] **Step 1: Write failing tests for all three**

`JitTeamScraperTest.java`:
```java
package com.piotrcapecki.openclaw.skill.career.scraper;

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
    }
}
```

`AmazonJobsScraperTest.java`:
```java
package com.piotrcapecki.openclaw.skill.career.scraper;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class AmazonJobsScraperTest {

    @Test
    void parsesJobsFromHtml() {
        String html = """
            <div class="job-tile">
              <h3 class="job-title"><a href="/pl/jobs/123">Software Dev Engineer I</a></h3>
              <div class="location-and-id"><span>Gdańsk, Poland</span></div>
            </div>
            """;

        AmazonJobsScraper scraper = new AmazonJobsScraper() {
            @Override protected Document fetchDocument() { return Jsoup.parse(html); }
        };

        List<RawJobOffer> offers = scraper.scrape();

        assertThat(offers).hasSize(1);
        assertThat(offers.get(0).company()).isEqualTo("Amazon");
    }
}
```

`DeloitteJobsScraperTest.java`:
```java
package com.piotrcapecki.openclaw.skill.career.scraper;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class DeloitteJobsScraperTest {

    @Test
    void parsesJobsFromHtml() {
        String html = """
            <div class="apply-grid-card">
              <a class="job-title" href="/jobs/java-dev-123">Java Developer</a>
              <span class="job-location">Warszawa, Polska (Remote)</span>
            </div>
            """;

        DeloitteJobsScraper scraper = new DeloitteJobsScraper() {
            @Override protected Document fetchDocument() { return Jsoup.parse(html); }
        };

        List<RawJobOffer> offers = scraper.scrape();

        assertThat(offers).hasSize(1);
        assertThat(offers.get(0).company()).isEqualTo("Deloitte");
    }
}
```

- [ ] **Step 2: Run tests to verify they all fail**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw.skill.career.scraper.JitTeamScraperTest" \
  --tests "com.piotrcapecki.openclaw.skill.career.scraper.AmazonJobsScraperTest" \
  --tests "com.piotrcapecki.openclaw.skill.career.scraper.DeloitteJobsScraperTest"
```

Expected: all FAIL

- [ ] **Step 3: Implement `JitTeamScraper.java`**

```java
package com.piotrcapecki.openclaw.skill.career.scraper;

import com.piotrcapecki.openclaw.skill.career.domain.JobSource;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.util.*;

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

    @Override public JobSource getSource() { return JobSource.JIT; }

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
                result.add(new RawJobOffer(title,
                        company.isBlank() ? "JIT.team" : company,
                        location.isBlank() ? "Gdańsk" : location,
                        url, title + " — " + location, JobSource.JIT));
            }
            return result;
        } catch (Exception e) {
            log.error("JIT.team scraper error: {}", e.getMessage());
            throw new RuntimeException("JIT.team scrape failed", e);
        }
    }

    protected Document fetchDocument() throws Exception {
        return Jsoup.connect(JOBS_URL).userAgent("Mozilla/5.0").timeout(15_000).get();
    }
}
```

- [ ] **Step 4: Implement `AmazonJobsScraper.java`**

```java
package com.piotrcapecki.openclaw.skill.career.scraper;

import com.piotrcapecki.openclaw.skill.career.domain.JobSource;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.util.*;

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

    @Override public JobSource getSource() { return JobSource.AMAZON; }

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
                String location = el.select(LOCATION_SEL).text("Poland");
                result.add(new RawJobOffer(title, "Amazon", location, url,
                        title + " at Amazon — " + location, JobSource.AMAZON));
            }
            return result;
        } catch (Exception e) {
            log.error("Amazon Jobs scraper error: {}", e.getMessage());
            throw new RuntimeException("Amazon Jobs scrape failed", e);
        }
    }

    protected Document fetchDocument() throws Exception {
        return Jsoup.connect(SEARCH_URL).userAgent("Mozilla/5.0").timeout(15_000).get();
    }
}
```

- [ ] **Step 5: Implement `DeloitteJobsScraper.java`**

```java
package com.piotrcapecki.openclaw.skill.career.scraper;

import com.piotrcapecki.openclaw.skill.career.domain.JobSource;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@Slf4j
public class DeloitteJobsScraper implements JobScraper {

    private static final String BASE_URL   = "https://jobsearch.deloitte.com";
    private static final String SEARCH_URL = BASE_URL + "/jobs#countries=Poland&category=Technology";
    // ⚠️ Verify selectors — Deloitte may render via JavaScript; inspect XHR calls if Jsoup returns empty
    private static final String OFFER_SEL    = "div.apply-grid-card";
    private static final String TITLE_SEL    = "a.job-title";
    private static final String LOCATION_SEL = "span.job-location";

    @Override public JobSource getSource() { return JobSource.DELOITTE; }

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
                String location = el.select(LOCATION_SEL).text("Poland");
                result.add(new RawJobOffer(title, "Deloitte", location, url,
                        title + " at Deloitte — " + location, JobSource.DELOITTE));
            }
            return result;
        } catch (Exception e) {
            log.error("Deloitte scraper error: {}", e.getMessage());
            throw new RuntimeException("Deloitte scrape failed", e);
        }
    }

    protected Document fetchDocument() throws Exception {
        return Jsoup.connect(SEARCH_URL).userAgent("Mozilla/5.0").timeout(15_000).get();
    }
}
```

- [ ] **Step 6: Run all three tests to verify they pass**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw.skill.career.scraper.JitTeamScraperTest" \
  --tests "com.piotrcapecki.openclaw.skill.career.scraper.AmazonJobsScraperTest" \
  --tests "com.piotrcapecki.openclaw.skill.career.scraper.DeloitteJobsScraperTest"
```

Expected: all PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/piotrcapecki/openclaw/skill/career/scraper/JitTeamScraper.java \
  src/main/java/com/piotrcapecki/openclaw/skill/career/scraper/AmazonJobsScraper.java \
  src/main/java/com/piotrcapecki/openclaw/skill/career/scraper/DeloitteJobsScraper.java \
  src/test/java/com/piotrcapecki/openclaw/skill/career/scraper/
git commit -m "feat(career): implement JIT.team, Amazon, Deloitte Jsoup scrapers"
```

---

## Task 13: CareerAgent — CareerScoringService

**Files:**
- Create: `src/main/java/com/piotrcapecki/openclaw/skill/career/dto/ScoreResultDto.java`
- Create: `src/main/java/com/piotrcapecki/openclaw/skill/career/service/CareerScoringService.java`
- Create: `src/test/java/com/piotrcapecki/openclaw/skill/career/service/CareerScoringServiceTest.java`

- [ ] **Step 1: Create `ScoreResultDto.java`**

```java
package com.piotrcapecki.openclaw.skill.career.dto;

public record ScoreResultDto(String offerId, String score, String reason) {}
```

- [ ] **Step 2: Write failing tests**

```java
package com.piotrcapecki.openclaw.skill.career.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piotrcapecki.openclaw.core.ai.OpenRouterClient;
import com.piotrcapecki.openclaw.skill.career.domain.*;
import com.piotrcapecki.openclaw.skill.career.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CareerScoringServiceTest {

    @Mock OpenRouterClient openRouterClient;
    @Mock JobOfferRepository jobOfferRepository;
    @Mock UserProfileRepository userProfileRepository;
    @InjectMocks CareerScoringService service;

    @Test
    void updatesOfferScoreFromOpenRouterResponse() {
        UUID offerId = UUID.randomUUID();
        JobOffer offer = JobOffer.builder()
                .id(offerId).title("Junior Java Dev").company("Nordea")
                .location("Gdańsk").url("https://example.com").description("Java role")
                .score(OfferScore.PENDING_SCORE).build();

        UserProfile profile = UserProfile.builder()
                .stack(List.of("Java", "Spring Boot"))
                .level(List.of("junior"))
                .locations(List.of("Gdańsk", "remote"))
                .preferences("Backend REST APIs")
                .build();

        String claudeResponse = """
            [{"offerId":"%s","score":"STRONG","reason":"Idealne dopasowanie"}]
            """.formatted(offerId);

        when(jobOfferRepository.findByScore(OfferScore.PENDING_SCORE)).thenReturn(List.of(offer));
        when(userProfileRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(profile));
        when(openRouterClient.complete(anyString())).thenReturn(claudeResponse);

        service.scoreAllPending();

        ArgumentCaptor<JobOffer> captor = ArgumentCaptor.forClass(JobOffer.class);
        verify(jobOfferRepository).save(captor.capture());
        assertThat(captor.getValue().getScore()).isEqualTo(OfferScore.STRONG);
        assertThat(captor.getValue().getScoreReason()).isEqualTo("Idealne dopasowanie");
    }

    @Test
    void doesNothingWhenNoPendingOffers() {
        when(jobOfferRepository.findByScore(OfferScore.PENDING_SCORE)).thenReturn(List.of());
        service.scoreAllPending();
        verify(openRouterClient, never()).complete(anyString());
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw.skill.career.service.CareerScoringServiceTest"
```

Expected: FAIL

- [ ] **Step 4: Implement `CareerScoringService.java`**

```java
package com.piotrcapecki.openclaw.skill.career.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piotrcapecki.openclaw.core.ai.OpenRouterClient;
import com.piotrcapecki.openclaw.skill.career.domain.*;
import com.piotrcapecki.openclaw.skill.career.dto.ScoreResultDto;
import com.piotrcapecki.openclaw.skill.career.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class CareerScoringService {

    private final OpenRouterClient openRouterClient;
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
                .orElseThrow(() -> new IllegalStateException(
                        "No user profile configured. Set one up via PATCH /api/career/profile"));

        try {
            String prompt = buildPrompt(profile, pending);
            String response = openRouterClient.complete(prompt);
            List<ScoreResultDto> results = parseResults(response);

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
                    log.warn("Unknown score '{}' for offer {}", result.score(), result.offerId());
                }
            }
        } catch (Exception e) {
            log.error("Scoring failed — offers remain PENDING_SCORE: {}", e.getMessage());
        }
    }

    private String buildPrompt(UserProfile profile, List<JobOffer> offers) {
        try {
            List<Map<String, String>> offerList = offers.stream().map(o -> Map.of(
                    "offerId", o.getId().toString(),
                    "title",   nvl(o.getTitle()),
                    "company", nvl(o.getCompany()),
                    "location",nvl(o.getLocation()),
                    "description", nvl(o.getDescription())
            )).toList();

            return """
                You are evaluating job offers for a candidate with the following profile:
                - Stack: %s
                - Level: %s
                - Locations: %s
                - Preferences: %s

                For each offer below, return ONLY a JSON array. Each element must have:
                  "offerId" (string), "score" (STRONG | MEDIUM | SKIP), "reason" (Polish, max 100 chars)

                No markdown, no explanation — only the JSON array.

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
            throw new RuntimeException("Failed to build scoring prompt", e);
        }
    }

    private List<ScoreResultDto> parseResults(String content) throws Exception {
        int start = content.indexOf('[');
        int end = content.lastIndexOf(']');
        if (start == -1 || end == -1)
            throw new RuntimeException("No JSON array found in OpenRouter response");
        return objectMapper.readValue(content.substring(start, end + 1), new TypeReference<>() {});
    }

    private String nvl(String v) { return v != null ? v : ""; }
}
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw.skill.career.service.CareerScoringServiceTest"
```

Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/piotrcapecki/openclaw/skill/career/service/CareerScoringService.java \
  src/main/java/com/piotrcapecki/openclaw/skill/career/dto/ScoreResultDto.java \
  src/test/java/com/piotrcapecki/openclaw/skill/career/service/CareerScoringServiceTest.java
git commit -m "feat(career): implement CareerScoringService using OpenRouterClient"
```

---

## Task 14: CareerAgent — Digest & Scheduler

**Files:**
- Create: `src/main/java/com/piotrcapecki/openclaw/skill/career/scheduler/CareerScheduler.java`
- Create: `src/main/java/com/piotrcapecki/openclaw/skill/career/dto/ScrapeRunDto.java`

- [ ] **Step 1: Create `ScrapeRunDto.java`**

```java
package com.piotrcapecki.openclaw.skill.career.dto;

import com.piotrcapecki.openclaw.skill.career.domain.ScrapeRun;
import java.time.LocalDateTime;
import java.util.UUID;

public record ScrapeRunDto(
        UUID id, LocalDateTime startedAt, LocalDateTime finishedAt,
        Integer newOffersCount, String status
) {
    public static ScrapeRunDto from(ScrapeRun run) {
        return new ScrapeRunDto(run.getId(), run.getStartedAt(), run.getFinishedAt(),
                run.getNewOffersCount(), run.getStatus());
    }
}
```

- [ ] **Step 2: Implement `CareerScheduler.java`**

The scheduler builds the daily Telegram digest and delegates sending to `TelegramClient`.

```java
package com.piotrcapecki.openclaw.skill.career.scheduler;

import com.piotrcapecki.openclaw.core.notification.TelegramClient;
import com.piotrcapecki.openclaw.skill.career.domain.*;
import com.piotrcapecki.openclaw.skill.career.repository.JobOfferRepository;
import com.piotrcapecki.openclaw.skill.career.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class CareerScheduler {

    private final JobIngestionService jobIngestionService;
    private final CareerScoringService careerScoringService;
    private final TelegramClient telegramClient;
    private final JobOfferRepository jobOfferRepository;

    @Scheduled(cron = "0 0 8 * * *")
    public void runDailyPipeline() {
        log.info("[CareerAgent] Starting daily pipeline...");
        try {
            jobIngestionService.ingest();
            careerScoringService.scoreAllPending();
            sendDigest();
            log.info("[CareerAgent] Daily pipeline complete");
        } catch (Exception e) {
            log.error("[CareerAgent] Pipeline error: {}", e.getMessage());
        }
    }

    public void sendDigest() {
        List<JobOffer> strong = jobOfferRepository.findBySentAtIsNullAndScore(OfferScore.STRONG);
        List<JobOffer> medium = jobOfferRepository.findBySentAtIsNullAndScore(OfferScore.MEDIUM);

        if (strong.isEmpty() && medium.isEmpty()) {
            log.info("[CareerAgent] No unsent offers today");
            return;
        }

        String message = buildDigest(strong, medium);
        telegramClient.send(message);

        LocalDateTime now = LocalDateTime.now();
        strong.forEach(o -> { o.setSentAt(now); jobOfferRepository.save(o); });
        medium.forEach(o -> { o.setSentAt(now); jobOfferRepository.save(o); });
    }

    private String buildDigest(List<JobOffer> strong, List<JobOffer> medium) {
        StringBuilder sb = new StringBuilder();
        sb.append("🔍 <b>OpenClaw — Daily Job Report [")
          .append(LocalDate.now()).append("]</b>\n\n");

        if (!strong.isEmpty()) {
            sb.append("💚 <b>Mocne dopasowania (").append(strong.size()).append(")</b>\n");
            for (JobOffer o : strong) appendOffer(sb, o);
            sb.append("\n");
        }

        if (!medium.isEmpty()) {
            sb.append("🟡 <b>Średnie dopasowania (").append(medium.size()).append(")</b>\n");
            for (JobOffer o : medium) appendOffer(sb, o);
        }

        return sb.toString();
    }

    private void appendOffer(StringBuilder sb, JobOffer o) {
        sb.append("• ").append(telegramClient.escapeHtml(o.getTitle()))
          .append(" @ ").append(telegramClient.escapeHtml(o.getCompany()))
          .append(" — ").append(telegramClient.escapeHtml(o.getLocation())).append("\n")
          .append("  <a href=\"").append(o.getUrl()).append("\">Zobacz ofertę</a>\n");
    }
}
```

- [ ] **Step 3: Verify project compiles**

```bash
./gradlew compileJava
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/piotrcapecki/openclaw/skill/career/scheduler/CareerScheduler.java \
  src/main/java/com/piotrcapecki/openclaw/skill/career/dto/ScrapeRunDto.java
git commit -m "feat(career): add CareerScheduler with daily pipeline and Telegram digest"
```

---

## Task 15: CareerAgent — REST API

**Files:**
- Create: `src/main/java/com/piotrcapecki/openclaw/skill/career/dto/UserProfileDto.java`
- Create: `src/main/java/com/piotrcapecki/openclaw/skill/career/dto/JobOfferDto.java`
- Create: `src/main/java/com/piotrcapecki/openclaw/skill/career/api/ProfileController.java`
- Create: `src/main/java/com/piotrcapecki/openclaw/skill/career/api/OffersController.java`
- Create: `src/main/java/com/piotrcapecki/openclaw/skill/career/api/ScrapeController.java`
- Create: `src/test/java/com/piotrcapecki/openclaw/skill/career/api/ProfileControllerTest.java`
- Create: `src/test/java/com/piotrcapecki/openclaw/skill/career/api/OffersControllerTest.java`
- Create: `src/test/java/com/piotrcapecki/openclaw/skill/career/api/ScrapeControllerTest.java`

- [ ] **Step 1: Create DTOs**

`UserProfileDto.java`:
```java
package com.piotrcapecki.openclaw.skill.career.dto;

import com.piotrcapecki.openclaw.skill.career.domain.UserProfile;
import java.util.List;

public record UserProfileDto(List<String> stack, List<String> level,
                              List<String> locations, String preferences) {
    public static UserProfileDto from(UserProfile p) {
        return new UserProfileDto(p.getStack(), p.getLevel(), p.getLocations(), p.getPreferences());
    }
}
```

`JobOfferDto.java`:
```java
package com.piotrcapecki.openclaw.skill.career.dto;

import com.piotrcapecki.openclaw.skill.career.domain.JobOffer;
import java.time.LocalDateTime;
import java.util.UUID;

public record JobOfferDto(UUID id, String source, String title, String company,
                           String location, String url, String score, String scoreReason,
                           LocalDateTime foundAt, LocalDateTime sentAt) {
    public static JobOfferDto from(JobOffer o) {
        return new JobOfferDto(o.getId(),
                o.getSource() != null ? o.getSource().name() : null,
                o.getTitle(), o.getCompany(), o.getLocation(), o.getUrl(),
                o.getScore() != null ? o.getScore().name() : null,
                o.getScoreReason(), o.getFoundAt(), o.getSentAt());
    }
}
```

- [ ] **Step 2: Write failing controller tests**

`ProfileControllerTest.java`:
```java
package com.piotrcapecki.openclaw.skill.career.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piotrcapecki.openclaw.skill.career.domain.UserProfile;
import com.piotrcapecki.openclaw.skill.career.repository.UserProfileRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.*;

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
    void getProfileReturns200() throws Exception {
        UserProfile profile = UserProfile.builder().id(1L)
                .stack(List.of("Java")).level(List.of("junior"))
                .locations(List.of("Gdańsk")).preferences("Backend").build();
        when(userProfileRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(profile));

        mockMvc.perform(get("/api/career/profile").header("X-API-Key", "changeme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stack[0]").value("Java"));
    }

    @Test
    void getProfileReturns404WhenMissing() throws Exception {
        when(userProfileRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/career/profile").header("X-API-Key", "changeme"))
                .andExpect(status().isNotFound());
    }

    @Test
    void patchProfileCreatesOrUpdates() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "stack", List.of("Java", "Spring"),
                "level", List.of("junior"),
                "locations", List.of("Gdańsk"),
                "preferences", "Backend REST"
        ));
        when(userProfileRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());
        when(userProfileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mockMvc.perform(patch("/api/career/profile")
                        .header("X-API-Key", "changeme")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preferences").value("Backend REST"));
    }
}
```

`OffersControllerTest.java`:
```java
package com.piotrcapecki.openclaw.skill.career.api;

import com.piotrcapecki.openclaw.skill.career.domain.*;
import com.piotrcapecki.openclaw.skill.career.repository.JobOfferRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.*;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(OffersController.class)
class OffersControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean JobOfferRepository jobOfferRepository;

    @Test
    void getOffersReturnsList() throws Exception {
        JobOffer offer = JobOffer.builder().id(UUID.randomUUID())
                .title("Junior Java Dev").company("Nordea").location("Gdańsk")
                .source(JobSource.JUSTJOINIT).score(OfferScore.STRONG).build();
        when(jobOfferRepository.findAllByOrderByFoundAtDesc()).thenReturn(List.of(offer));

        mockMvc.perform(get("/api/career/offers").header("X-API-Key", "changeme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].score").value("STRONG"));
    }

    @Test
    void getOfferByIdReturns404WhenNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        when(jobOfferRepository.findById(id)).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/career/offers/" + id).header("X-API-Key", "changeme"))
                .andExpect(status().isNotFound());
    }
}
```

`ScrapeControllerTest.java`:
```java
package com.piotrcapecki.openclaw.skill.career.api;

import com.piotrcapecki.openclaw.skill.career.domain.ScrapeRun;
import com.piotrcapecki.openclaw.skill.career.repository.ScrapeRunRepository;
import com.piotrcapecki.openclaw.skill.career.scheduler.CareerScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.*;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ScrapeController.class)
class ScrapeControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean CareerScheduler careerScheduler;
    @MockitoBean ScrapeRunRepository scrapeRunRepository;

    @Test
    void postRunTriggersPipeline() throws Exception {
        mockMvc.perform(post("/api/career/scrape/run").header("X-API-Key", "changeme"))
                .andExpect(status().isOk());
        verify(careerScheduler, times(1)).runDailyPipeline();
    }

    @Test
    void getRunsReturnsHistory() throws Exception {
        ScrapeRun run = ScrapeRun.builder().id(UUID.randomUUID())
                .startedAt(LocalDateTime.now()).finishedAt(LocalDateTime.now())
                .newOffersCount(5).status("SUCCESS").build();
        when(scrapeRunRepository.findAllByOrderByStartedAtDesc()).thenReturn(List.of(run));

        mockMvc.perform(get("/api/career/scrape/runs").header("X-API-Key", "changeme"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("SUCCESS"))
                .andExpect(jsonPath("$[0].newOffersCount").value(5));
    }
}
```

- [ ] **Step 3: Run all controller tests to verify they fail**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw.skill.career.api.*"
```

Expected: all FAIL

- [ ] **Step 4: Implement `ProfileController.java`**

```java
package com.piotrcapecki.openclaw.skill.career.api;

import com.piotrcapecki.openclaw.skill.career.domain.UserProfile;
import com.piotrcapecki.openclaw.skill.career.dto.UserProfileDto;
import com.piotrcapecki.openclaw.skill.career.repository.UserProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/career/profile")
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

        if (updates.containsKey("stack"))       profile.setStack(castList(updates.get("stack")));
        if (updates.containsKey("level"))       profile.setLevel(castList(updates.get("level")));
        if (updates.containsKey("locations"))   profile.setLocations(castList(updates.get("locations")));
        if (updates.containsKey("preferences")) profile.setPreferences((String) updates.get("preferences"));

        profile.setUpdatedAt(LocalDateTime.now());
        return ResponseEntity.ok(UserProfileDto.from(userProfileRepository.save(profile)));
    }

    @SuppressWarnings("unchecked")
    private List<String> castList(Object value) { return (List<String>) value; }
}
```

- [ ] **Step 5: Implement `OffersController.java`**

```java
package com.piotrcapecki.openclaw.skill.career.api;

import com.piotrcapecki.openclaw.skill.career.dto.JobOfferDto;
import com.piotrcapecki.openclaw.skill.career.repository.JobOfferRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/career/offers")
@RequiredArgsConstructor
public class OffersController {

    private final JobOfferRepository jobOfferRepository;

    @GetMapping
    public ResponseEntity<List<JobOfferDto>> getOffers() {
        return ResponseEntity.ok(jobOfferRepository.findAllByOrderByFoundAtDesc()
                .stream().map(JobOfferDto::from).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<JobOfferDto> getOffer(@PathVariable UUID id) {
        return jobOfferRepository.findById(id)
                .map(JobOfferDto::from).map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
```

- [ ] **Step 6: Implement `ScrapeController.java`**

```java
package com.piotrcapecki.openclaw.skill.career.api;

import com.piotrcapecki.openclaw.skill.career.dto.ScrapeRunDto;
import com.piotrcapecki.openclaw.skill.career.repository.ScrapeRunRepository;
import com.piotrcapecki.openclaw.skill.career.scheduler.CareerScheduler;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/career/scrape")
@RequiredArgsConstructor
public class ScrapeController {

    private final CareerScheduler careerScheduler;
    private final ScrapeRunRepository scrapeRunRepository;

    @PostMapping("/run")
    public ResponseEntity<Void> triggerScrape() {
        careerScheduler.runDailyPipeline();
        return ResponseEntity.ok().build();
    }

    @GetMapping("/runs")
    public ResponseEntity<List<ScrapeRunDto>> getRuns() {
        return ResponseEntity.ok(scrapeRunRepository.findAllByOrderByStartedAtDesc()
                .stream().map(ScrapeRunDto::from).toList());
    }
}
```

- [ ] **Step 7: Run all controller tests to verify they pass**

```bash
./gradlew test --tests "com.piotrcapecki.openclaw.skill.career.api.*"
```

Expected: all PASS

- [ ] **Step 8: Run full test suite**

```bash
./gradlew test
```

Expected: all tests PASS, `BUILD SUCCESSFUL`

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/piotrcapecki/openclaw/skill/career/api/ \
  src/main/java/com/piotrcapecki/openclaw/skill/career/dto/ \
  src/test/java/com/piotrcapecki/openclaw/skill/career/api/
git commit -m "feat(career): add REST API — profile, offers, scrape endpoints"
```

---

## Post-Implementation Checklist

- [ ] Set environment variables on the VPS: `DB_USERNAME`, `DB_PASSWORD`, `OPENROUTER_API_KEY`, `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`, `APP_API_KEY`
- [ ] Create a Telegram bot via [@BotFather](https://t.me/BotFather) and get the token
- [ ] Get your `chat_id` via `https://api.telegram.org/bot<TOKEN>/getUpdates` after sending `/start` to your bot
- [ ] Set your profile: `PATCH /api/career/profile`
- [ ] Trigger a manual run: `POST /api/career/scrape/run`
- [ ] Verify Jsoup scrapers return results (JIT.team, Amazon, Deloitte selectors may need adjustment after inspecting live HTML)
- [ ] Verify `GET /api/career/offers` shows scored offers after the pipeline runs
