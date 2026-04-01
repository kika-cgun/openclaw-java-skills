# Job Scraping & Scoring Feature — Design Spec

**Date:** 2026-04-01
**Project:** OpenClaw Career Agent
**Status:** Approved

---

## Overview

A scheduled feature within the OpenClaw Career Agent Spring Boot application that:
1. Scrapes job/internship listings from multiple sources daily
2. Deduplicates against PostgreSQL history
3. Scores new offers using Claude AI against a user-defined profile
4. Sends a single daily Telegram digest grouped by match quality

---

## Architecture

```
┌─────────────────────────────────────────────────────┐
│                  Scheduler (cron)                    │
└──────────────────────┬──────────────────────────────┘
                       │ daily trigger
┌──────────────────────▼──────────────────────────────┐
│              JobIngestionService                     │
│  1. calls all scrapers                               │
│  2. deduplicates by URL hash vs DB                   │
│  3. saves new offers as PENDING_SCORE                │
└──────────┬───────────────────────────┬──────────────┘
           │                           │
┌──────────▼──────┐         ┌──────────▼──────────────┐
│  JobScraper[]   │         │    ScoringService        │
│  - JustJoinIT   │         │  batch prompt → Claude   │
│  - NoFluffJobs  │         │  returns score + reason  │
│  - JIT.team     │         └──────────┬───────────────┘
│  - Amazon       │                    │
│  - Deloitte     │         ┌──────────▼───────────────┐
└─────────────────┘         │   TelegramNotifier       │
                            │  builds daily digest     │
                            │  💚 STRONG / 🟡 MEDIUM   │
                            └──────────────────────────┘
```

---

## Data Sources

| Source | Integration method | Notes |
|--------|--------------------|-------|
| JustJoinIT | Public REST API | `GET /api/offers` |
| No Fluff Jobs | Public REST API | `POST /api/v2/postings` with filters |
| JIT.team | HTTP scraping (Jsoup) | Simple HTML structure |
| Amazon Jobs | HTTP scraping (Jsoup) | Careers page, paginated |
| Deloitte Jobs | HTTP scraping (Jsoup) | Careers page |
| LinkedIn | **Not in scope** | Blocked by anti-scraping; add later if needed |

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

Spring autowires all `@Component` implementations as `List<JobScraper>`. Adding a new source requires only a new class — no changes to `JobIngestionService`.

---

## Database Schema

```sql
-- User profile (editable via REST)
user_profile (
  id           BIGSERIAL PRIMARY KEY,
  stack        TEXT[],       -- e.g. ["Java", "Spring Boot", "SQL"]
  level        TEXT[],       -- e.g. ["intern", "junior"]
  locations    TEXT[],       -- e.g. ["Gdańsk", "Gdynia", "Sopot", "remote"]
  preferences  TEXT,         -- free text fed directly to Claude as context
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

## AI Scoring

`ScoringService` collects all `PENDING_SCORE` offers after ingestion and sends them to Claude in a single batch prompt. Batch approach reduces API calls and cost vs. one-call-per-offer.

**Prompt structure:**
```
You are evaluating job offers for a candidate with the following profile:
- Stack: {stack}
- Level: {level}
- Locations: {locations}
- Preferences: {preferences}

For each offer below, return a JSON array with score and reason.
Score values: STRONG | MEDIUM | SKIP

Offers:
[{ "offerId": "...", "title": "...", "company": "...", "location": "...", "description": "..." }, ...]
```

**Claude response format:**
```json
[
  { "offerId": "abc-123", "score": "STRONG", "reason": "Java 21, Spring Boot, remote, junior — ideal match" },
  { "offerId": "def-456", "score": "MEDIUM", "reason": "Java yes, but requires 2 years exp and Warsaw only" },
  { "offerId": "ghi-789", "score": "SKIP",   "reason": "React frontend, not backend Java" }
]
```

After scoring, `job_offers.score` and `job_offers.score_reason` are updated.

---

## Telegram Digest

One message per day, sent only when there are STRONG or MEDIUM offers unsent (`sent_at IS NULL`).

**Message format:**
```
🔍 OpenClaw — Daily Job Report [2026-04-01]

💚 Strong matches (2)
• Junior Java Developer @ Nordea — Gdańsk/remote
  https://...
• Backend Intern @ JIT.team — Trójmiasto
  https://...

🟡 Medium matches (3)
• Java Developer @ Deloitte — Warszawa (remote possible)
  https://...
...

📊 Scanned 47 offers across 5 sources. 12 new today.
```

SKIP offers are never sent to Telegram.

---

## REST API

All endpoints secured with Spring Security using a static API key passed as `X-API-Key` header (configured in `application.yaml`). Sufficient for a single-user VPS deployment.

```
# User profile
GET    /api/profile              — get current profile
PATCH  /api/profile              — partial update of profile

# Job offers
GET    /api/offers               — list offers (query params: score, source, from, to)
GET    /api/offers/{id}          — offer detail

# Operations
POST   /api/scrape/run           — trigger manual scrape run
GET    /api/scrape/runs          — list scrape run history
```

---

## Scheduling

Single `@Scheduled(cron = "0 0 8 * * *")` bean (8:00 AM daily, server timezone) that:
1. Triggers `JobIngestionService.ingest()`
2. Triggers `ScoringService.scoreAllPending()`
3. Triggers `TelegramNotifier.sendDailyDigest()`

Each step is independently callable via REST for manual triggering and development.

---

## Error Handling

- **Per-scraper failures** are caught and logged; failed sources are recorded in `scrape_runs.status = PARTIAL`. Other sources continue.
- **Claude API failure** — offers remain `PENDING_SCORE`; retried on next run.
- **Telegram failure** — logged, `sent_at` not updated; retried on next run.

---

## Key Dependencies to Add

```kotlin
// build.gradle.kts additions
implementation("org.jsoup:jsoup:1.17.2")
implementation("com.squareup.okhttp3:okhttp:4.12.0")
implementation("com.anthropic:sdk:0.8.0")   // Claude Java SDK
implementation("org.springframework.boot:spring-boot-starter-data-jpa")
implementation("org.flywaydb:flyway-core")  // DB migrations
```

Telegram notification via direct HTTPS call to Bot API (no extra library needed).

---

## Out of Scope

- LinkedIn scraping
- Web UI / dashboard (REST API only)
- Multi-user support
- CV parsing
