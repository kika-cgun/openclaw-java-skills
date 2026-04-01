# CareerAgent Skill — Design Spec

**Date:** 2026-04-01
**Project:** OpenClaw Platform
**Status:** Approved

---

## Context

OpenClaw is a **multi-skill AI agent platform** running on a personal VPS. Each skill is a self-contained module with its own data collection, AI evaluation, and notification logic. All skills share platform-level infrastructure: an OpenRouter AI client, a Telegram notifier, and Spring Security.

Current skills:
- **CareerAgent** ← this spec
- **PersonalOps** ← planned (VPS monitoring, backups, service health, alerts)

---

## CareerAgent Overview

A scheduled skill within the OpenClaw platform that:
1. Scrapes job/internship listings from multiple sources daily
2. Deduplicates against PostgreSQL history
3. Scores new offers using Claude AI (via OpenRouter) against a user-defined profile
4. Sends a single daily Telegram digest grouped by match quality

---

## Platform Architecture

```
┌──────────────────────────────────────────────────────────────┐
│                      OpenClaw Platform                        │
│                                                              │
│  ┌───────────────────────┐   ┌──────────────────────────┐   │
│  │    CareerAgent Skill  │   │   PersonalOps Skill      │   │
│  │                       │   │   (planned)              │   │
│  │  Scrapers             │   │                          │   │
│  │  JobIngestionService  │   │  Monitors                │   │
│  │  CareerScoringService │   │  OpsScoringService       │   │
│  │  CareerScheduler      │   │  OpsScheduler            │   │
│  │  REST /api/career/**  │   │  REST /api/ops/**        │   │
│  └──────────┬────────────┘   └──────────┬───────────────┘   │
│             │                           │                    │
│  ┌──────────▼───────────────────────────▼───────────────┐   │
│  │                  Core Infrastructure                  │   │
│  │   OpenRouterClient │ TelegramClient │ SecurityConfig  │   │
│  └──────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────┘
```

---

## CareerAgent — Internal Flow

```
CareerScheduler (@Scheduled daily)
    │
    ▼
JobIngestionService
    ├── calls all JobScraper implementations
    ├── deduplicates by URL hash vs DB
    └── saves new offers as PENDING_SCORE
    │
    ▼
CareerScoringService
    ├── loads PENDING_SCORE offers + user profile
    ├── builds batch prompt
    └── calls OpenRouterClient → Claude scores all offers
    │
    ▼
TelegramClient (core)
    └── sends daily digest: 💚 STRONG / 🟡 MEDIUM
```

---

## Data Sources

| Source | Integration method | Notes |
|--------|--------------------|-------|
| JustJoinIT | Public REST API | `GET /api/offers` |
| No Fluff Jobs | Public REST API | `POST /api/v2/postings` |
| JIT.team | HTTP scraping (Jsoup) | Simple HTML structure |
| Amazon Jobs | HTTP scraping (Jsoup) | Careers page, paginated |
| Deloitte Jobs | HTTP scraping (Jsoup) | Careers page |
| LinkedIn | **Not in scope** | Anti-scraping; add later if needed |

---

## Scraper Interface

Every source implements a single contract:

```java
public interface JobScraper {
    JobSource getSource();
    List<RawJobOffer> scrape();
}
```

`RawJobOffer` fields: `title`, `company`, `location`, `url`, `description`, `source`.

Spring autowires all `@Component` implementations as `List<JobScraper>`. Adding a new source requires only a new class.

---

## Database Schema

```sql
-- User profile (editable via REST)
user_profile (
  id           BIGSERIAL PRIMARY KEY,
  stack        TEXT,         -- comma-separated, e.g. "Java,Spring Boot,SQL"
  level        TEXT,         -- comma-separated, e.g. "intern,junior"
  locations    TEXT,         -- comma-separated, e.g. "Gdańsk,Gdynia,Sopot,remote"
  preferences  TEXT,         -- free text fed to Claude as context
  updated_at   TIMESTAMP
)

-- Job offers
job_offers (
  id           UUID PRIMARY KEY,
  source       VARCHAR,      -- JUSTJOINIT | NOFLUFFJOBS | JIT | AMAZON | DELOITTE
  external_id  VARCHAR UNIQUE, -- SHA-256 hash of URL (deduplication key)
  title        VARCHAR,
  company      VARCHAR,
  location     VARCHAR,
  url          TEXT,
  description  TEXT,
  score        VARCHAR,      -- PENDING_SCORE | STRONG | MEDIUM | SKIP
  score_reason TEXT,
  found_at     TIMESTAMP,
  sent_at      TIMESTAMP     -- NULL until included in a Telegram digest
)

-- Scrape run log
scrape_runs (
  id               UUID PRIMARY KEY,
  started_at       TIMESTAMP,
  finished_at      TIMESTAMP,
  new_offers_count INT,
  status           VARCHAR   -- SUCCESS | PARTIAL | FAILED
)
```

---

## AI Scoring (via OpenRouter)

`CareerScoringService` collects all `PENDING_SCORE` offers and sends them to Claude in a single batch prompt through `OpenRouterClient`. Batch approach reduces API calls vs. one-call-per-offer.

**OpenRouter call:**
```
POST https://openrouter.ai/api/v1/chat/completions
Authorization: Bearer <OPENROUTER_API_KEY>
{
  "model": "anthropic/claude-sonnet-4-5",
  "messages": [{ "role": "user", "content": "<prompt>" }]
}
```

**Prompt structure:**
```
You are evaluating job offers for a candidate with the following profile:
- Stack: {stack}
- Level: {level}
- Locations: {locations}
- Preferences: {preferences}

For each offer below, return ONLY a JSON array. Each element:
  "offerId" (string), "score" (STRONG | MEDIUM | SKIP), "reason" (Polish, max 100 chars)

Offers: [...]
```

**Response format:**
```json
[
  { "offerId": "abc-123", "score": "STRONG", "reason": "Java 21, Spring Boot, remote, junior — idealne" },
  { "offerId": "def-456", "score": "MEDIUM", "reason": "Java tak, ale wymaga 2 lat i tylko Warszawa" },
  { "offerId": "ghi-789", "score": "SKIP",   "reason": "React frontend, nie backend Java" }
]
```

---

## Telegram Digest

One message per day, only when STRONG or MEDIUM unsent offers exist (`sent_at IS NULL`). SKIP offers never sent.

```
🔍 OpenClaw — Daily Job Report [2026-04-02]

💚 Mocne dopasowania (2)
• Junior Java Developer @ Nordea — Gdańsk/remote
  https://...

🟡 Średnie dopasowania (3)
• Java Developer @ Deloitte — Warszawa (remote possible)
  https://...

📊 Przeskanowano 47 ofert z 5 źródeł. 12 nowych dzisiaj.
```

---

## REST API

Secured with `X-API-Key` header (static key from `application.yaml`).

```
# User profile
GET    /api/career/profile       — get current profile
PATCH  /api/career/profile       — partial update

# Job offers
GET    /api/career/offers        — list offers (query params: score, source, from, to)
GET    /api/career/offers/{id}   — offer detail

# Operations
POST   /api/career/scrape/run    — trigger manual scrape
GET    /api/career/scrape/runs   — scrape run history
```

> Future skills use `/api/ops/**`, `/api/finance/**` etc. — no conflicts.

---

## Scheduling

`CareerScheduler` runs daily at 8:00 AM (server timezone):

```java
@Scheduled(cron = "0 0 8 * * *")
public void runDailyPipeline() {
    jobIngestionService.ingest();
    careerScoringService.scoreAllPending();
    telegramClient.sendCareerDigest();
}
```

Each step also callable via REST for manual triggering and development.

---

## Error Handling

- **Per-scraper failures** — caught and logged; `scrape_runs.status = PARTIAL`. Other scrapers continue.
- **OpenRouter failure** — offers remain `PENDING_SCORE`; retried on next run.
- **Telegram failure** — logged, `sent_at` not updated; retried on next run.

---

## Package Structure

```
com.piotrcapecki.openclaw/
├── OpenClawApplication.java
├── core/
│   ├── ai/
│   │   └── OpenRouterClient.java        # generic AI client (used by all skills)
│   ├── notification/
│   │   └── TelegramClient.java          # generic Telegram sender (used by all skills)
│   └── config/
│       └── SecurityConfig.java          # X-API-Key filter
└── skill/
    └── career/
        ├── domain/
        │   ├── JobOffer.java
        │   ├── UserProfile.java
        │   ├── ScrapeRun.java
        │   ├── JobSource.java            (enum)
        │   ├── OfferScore.java           (enum)
        │   └── StringListConverter.java
        ├── repository/
        │   ├── JobOfferRepository.java
        │   ├── UserProfileRepository.java
        │   └── ScrapeRunRepository.java
        ├── scraper/
        │   ├── JobScraper.java           (interface)
        │   ├── RawJobOffer.java          (record)
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
            └── ScoreResultDto.java
```

---

## Key Dependencies

```kotlin
implementation("org.jsoup:jsoup:1.17.2")
implementation("com.squareup.okhttp3:okhttp:4.12.0")   // HTTP client + used by OpenRouter calls
implementation("org.springframework.boot:spring-boot-starter-data-jpa")
implementation("org.flywaydb:flyway-core")
implementation("org.flywaydb:flyway-database-postgresql")
// No Anthropic SDK — OpenRouter uses OpenAI-compatible REST API, called via OkHttp
```

Telegram notifications via `java.net.http.HttpClient` (no extra dependency).

---

## Out of Scope (this spec)

- LinkedIn scraping
- Web UI / dashboard (REST API only)
- Multi-user support
- CV parsing
- PersonalOps skill (separate spec)
