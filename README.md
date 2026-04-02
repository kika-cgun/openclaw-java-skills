# OpenClaw

A multi-skill AI agent platform built by **Piotr Capecki**. The first skill — **CareerAgent** — scrapes job and internship listings daily, scores them with Claude AI, and delivers a ranked digest via Telegram.

---

## Project Overview

OpenClaw is designed as a modular platform where each "skill" is a self-contained module that plugs into shared infrastructure (AI client, notifications, security). The platform runs on a VPS and operates autonomously without manual intervention.

**CareerAgent** currently aggregates listings from five sources, deduplicates them via SHA-256 URL hashing, scores each offer against a configurable user profile using Claude (via OpenRouter), and sends a concise Telegram digest every morning.

---

## Architecture

```
openclaw/
├── core/                        # Shared infrastructure
│   ├── ai/OpenRouterClient      # OpenRouter REST client (OkHttp)
│   ├── notification/TelegramClient
│   └── config/
│       ├── CoreConfig           # OkHttpClient, ObjectMapper beans
│       ├── SecurityConfig       # X-API-Key filter for /api/**
│       └── OpenApiConfig        # Swagger/OpenAPI config
└── skill/career/                # CareerAgent skill
    ├── scraper/                 # Job scrapers
    │   ├── JustJoinItScraper        (REST API)
    │   ├── NoFluffJobsScraper       (REST API POST)
    │   ├── JitTeamScraper           (Jsoup)
    │   ├── AmazonJobsScraper        (Jsoup)
    │   └── DeloitteJobsScraper      (Jsoup)
    ├── service/
    │   ├── JobIngestionService      # Deduplication via SHA-256 URL hash
    │   └── CareerScoringService     # Batch AI scoring via OpenRouter
    ├── scheduler/CareerScheduler    # Daily pipeline at 08:00
    ├── api/                         # REST controllers
    │   ├── ProfileController        PATCH /api/career/profile
    │   ├── OffersController         GET   /api/career/offers[/{id}]
    │   └── ScrapeController         POST  /api/career/scrape/run
    ├── domain/                      # JPA entities: JobOffer, UserProfile, ScrapeRun
    └── repository/                  # Spring Data JPA repositories
```

Future skills (e.g. VPS monitoring, backup orchestration) drop into `skill/<name>/` without touching core.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 4, Spring Data JPA, Spring Security |
| Database | PostgreSQL + Flyway migrations |
| HTTP client | OkHttp 4.12.0 |
| HTML scraping | Jsoup 1.17.2 |
| AI | OpenRouter → Claude (`anthropic/claude-sonnet-4-5`) |
| Notifications | Telegram Bot API |
| Build | Gradle Kotlin DSL |
| Testing | JUnit 5, Mockito, AssertJ, H2 (in-memory) |

No Anthropic SDK dependency — OpenRouter's OpenAI-compatible REST API is called directly over OkHttp.

---

## Database Schema

Flyway manages schema migrations automatically on startup.

| Table | Key columns |
|---|---|
| `user_profile` | `stack`, `level`, `locations`, `preferences` (TEXT) |
| `job_offers` | UUID PK, `external_id` (SHA-256, UNIQUE), `score` (`PENDING_SCORE` / `STRONG` / `MEDIUM` / `SKIP`), `sent_at` |
| `scrape_runs` | UUID PK, `started_at`, `finished_at`, `new_offers_count`, `status` |

---

## API Reference

All endpoints require an `X-API-Key` header.

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/career/profile` | Retrieve the current user profile |
| `PATCH` | `/api/career/profile` | Create or update profile (stack, preferences, etc.) |
| `GET` | `/api/career/offers` | List all offers, most recent first |
| `GET` | `/api/career/offers/{id}` | Get a single offer by UUID |
| `POST` | `/api/career/scrape/run` | Trigger the full pipeline manually |
| `GET` | `/api/career/scrape/runs` | List scrape run history |

Interactive documentation is available at `http://localhost:8080/swagger-ui.html`.

### Example requests

```bash
# Update profile
curl -X PATCH http://localhost:8080/api/career/profile \
  -H "X-API-Key: $APP_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"stack": "Java, Spring Boot", "level": "junior", "locations": "Warsaw, remote"}'

# Trigger a scrape run
curl -X POST http://localhost:8080/api/career/scrape/run \
  -H "X-API-Key: $APP_API_KEY"

# List scored offers
curl http://localhost:8080/api/career/offers \
  -H "X-API-Key: $APP_API_KEY"
```

---

## Setup & Configuration

### Prerequisites

- Java 21
- PostgreSQL

### Environment variables

```
DB_USERNAME          PostgreSQL username        (default: openclaw)
DB_PASSWORD          PostgreSQL password
OPENROUTER_API_KEY   OpenRouter API key
OPENROUTER_MODEL     Model override             (default: anthropic/claude-sonnet-4-5)
TELEGRAM_BOT_TOKEN   Telegram Bot token from @BotFather
TELEGRAM_CHAT_ID     Telegram chat or channel ID
APP_API_KEY          Secret for X-API-Key header (required, no default)
```

Export these in your shell or configure them in a `.env` file / systemd unit on the VPS.

---

## Running Locally

```bash
# 1. Create the database
createdb openclaw

# 2. Set required environment variables
export DB_PASSWORD=...
export OPENROUTER_API_KEY=...
export TELEGRAM_BOT_TOKEN=...
export TELEGRAM_CHAT_ID=...
export APP_API_KEY=...

# 3. Start the application (Flyway runs migrations automatically)
./gradlew bootRun

# 4. Configure your profile
curl -X PATCH http://localhost:8080/api/career/profile \
  -H "X-API-Key: $APP_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"stack": "Java, Spring Boot", "level": "junior", "locations": "Warsaw, remote"}'

# 5. Trigger the first pipeline run
curl -X POST http://localhost:8080/api/career/scrape/run \
  -H "X-API-Key: $APP_API_KEY"
```

---

## Running Tests

Tests use an H2 in-memory database — no PostgreSQL installation required.

```bash
./gradlew test
```

Every service and controller was developed test-first. Scrapers expose an overridable `fetchDocument()` method so HTML responses can be injected in tests without mocking Jsoup's static API.

---

## Daily Pipeline

The scheduler fires at **08:00** and executes three stages:

1. **Scrape** — all five sources are queried in sequence; each raw listing is hashed (SHA-256 of the canonical URL) and inserted only if the hash is not already present in `job_offers`.
2. **Score** — every offer in `PENDING_SCORE` state is sent to Claude (via OpenRouter) in batches. The model evaluates the listing against the stored user profile and returns `STRONG`, `MEDIUM`, or `SKIP`.
3. **Notify** — a Telegram digest is composed from scored offers: 💚 STRONG offers appear first, followed by 🟡 MEDIUM offers. `SKIP` offers are never sent.

---

## Future Skills

The platform is built to accommodate additional skills without modifying core infrastructure. Planned additions include:

- **VPS Monitor** — resource usage alerts and uptime checks
- **Backup Agent** — scheduled database and file backups with status reporting
- **Finance Tracker** — expense ingestion and monthly summaries

Each skill lives under `skill/<name>/` and wires into the shared `OpenRouterClient`, `TelegramClient`, and security layer.

---

## Author

**Piotr Capecki** — [github.com/piotrcapecki](https://github.com/piotrcapecki)
