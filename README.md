# OpenClaw

A multi-skill AI agent platform built by **Piotr Capecki**. The first skill — **CareerAgent** — scrapes job and internship listings daily, scores them with an LLM via OpenRouter, and delivers a ranked digest via Telegram or OpenClaw plugin workflows.

---

## Project Overview

OpenClaw is designed as a modular platform where each "skill" is a self-contained module that plugs into shared infrastructure (AI client, notifications, security). The platform runs on a VPS and operates autonomously without manual intervention.

**CareerAgent** currently aggregates listings from six sources, deduplicates them via SHA-256 URL hashing, scores each offer against a configurable user profile using OpenRouter models, and sends a concise Telegram digest every morning.

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
    │   ├── BulldogjobScraper        (JSON feed)
    │   ├── PracujPlScraper          (REST API)
    │   ├── JitTeamScraper           (Jsoup)
    │   ├── AmazonJobsScraper        (Jsoup)
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
| AI | OpenRouter (`stepfun/step-3-5-flash:free` default; Claude optional) |
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
| `GET` | `/api/career/digest/status` | Digest readiness and telemetry summary |
| `GET` | `/api/career/digest/compact` | Compact digest payload for OpenClaw plugin |
| `POST` | `/api/career/digest/ack` | Mark digest offers as delivered |

Interactive documentation is available at:
- local JVM run: `http://localhost:8080/swagger-ui.html`
- Docker Compose host binding: `http://127.0.0.1:18080/swagger-ui.html`

### Example requests

```bash
# Update profile
curl -X PATCH http://localhost:8080/api/career/profile \
  -H "X-API-Key: $APP_API_KEY" \
  -H "Content-Type: application/json" \
  -d '{"stack": ["Java", "Spring Boot"], "level": ["junior"], "locations": ["Warsaw", "remote"], "preferences": "Product teams, backend focus"}'

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
OPENROUTER_MODEL     Model override             (default: stepfun/step-3-5-flash:free)
APP_TELEGRAM_ENABLED Enable Telegram digest     (default: false)
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
  -d '{"stack": ["Java", "Spring Boot"], "level": ["junior"], "locations": ["Warsaw", "remote"], "preferences": "Product teams, backend focus"}'

# 5. Trigger the first pipeline run
curl -X POST http://localhost:8080/api/career/scrape/run \
  -H "X-API-Key: $APP_API_KEY"
```

### Running with Docker Compose (VPS-friendly)

```bash
# 1. Copy and fill env
cp deploy/env.example .env

# 2. Start services (db + app)
docker compose up -d --build

# 3. API is exposed only on loopback host port 18080
curl -H "X-API-Key: $APP_API_KEY" http://127.0.0.1:18080/api/career/digest/status
```

Docker maps host `127.0.0.1:18080` -> container `app:8080`.

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

1. **Scrape** — all six sources are queried in sequence; each raw listing is hashed (SHA-256 of the canonical URL) and inserted only if the hash is not already present in `job_offers`.
2. **Score** — every offer in `PENDING_SCORE` state is sent to Claude (via OpenRouter) in batches. The model evaluates the listing against the stored user profile and returns `STRONG`, `MEDIUM`, or `SKIP`.
3. **Notify** — a Telegram digest is composed from scored offers: 💚 STRONG offers appear first, followed by 🟡 MEDIUM offers. `SKIP` offers are never sent.

---

## OpenClaw AI Plugin Integration

This repository includes a native OpenClaw plugin at `openclaw/career-digest-plugin`.

### Install plugin in OpenClaw

```bash
openclaw plugins install -l ./openclaw/career-digest-plugin
openclaw plugins enable career-digest
openclaw gateway restart
```

### Required plugin env in OpenClaw config

Set skill env values in `~/.openclaw/openclaw.json` (or equivalent profile config):

```json
{
  "skills": {
    "entries": {
      "career-digest": {
        "enabled": true,
        "env": {
          "CAREER_API_BASE_URL": "http://127.0.0.1:18080",
          "CAREER_API_KEY": "<same-value-as-APP_API_KEY>"
        }
      },
      "career-digest-run": {
        "enabled": true,
        "env": {
          "CAREER_API_BASE_URL": "http://127.0.0.1:18080",
          "CAREER_API_KEY": "<same-value-as-APP_API_KEY>"
        }
      },
      "career-digest-ack": {
        "enabled": true,
        "env": {
          "CAREER_API_BASE_URL": "http://127.0.0.1:18080",
          "CAREER_API_KEY": "<same-value-as-APP_API_KEY>"
        }
      }
    }
  }
}
```

### Example slash commands

```text
/career-digest limitStrong=5 limitMedium=5 onlyUnsent=true
/career-digest-run limitStrong=5 limitMedium=5 onlyUnsent=true autoAck=true
```

---

## CI / CD

GitHub Actions handles both integration and deployment automatically.

### CI — runs on every push and pull request

Tests are executed on every branch push and PR. A failing test suite blocks the merge.

```
.github/workflows/ci.yml
```

### CD — deploy triggered by a version tag

Push a semantic version tag to kick off a deploy to the VPS:

```bash
git tag v1.1.0
git push --tags
```

The workflow builds a fat JAR, copies it to `/opt/openclaw/releases/<tag>/` on the VPS via SCP, atomically swaps the `current.jar` symlink, and restarts the systemd service. If the service fails to start, the workflow exits non-zero.

A manual trigger (`workflow_dispatch`) is also available from the GitHub Actions tab — useful for hotfixes without a version bump.

### Required GitHub Secrets

| Secret | Description |
|---|---|
| `VPS_HOST` | VPS hostname or IP |
| `VPS_USER` | SSH user (must have `sudo systemctl restart openclaw` permission) |
| `VPS_SSH_KEY` | Private SSH key (add the public key to the VPS's `~/.ssh/authorized_keys`) |

### VPS first-time setup

```bash
# Clone the repo on your local machine, then from the deploy/ directory:
sudo bash deploy/vps-setup.sh deploy   # pass your SSH deploy username

# Fill in secrets
sudo nano /etc/openclaw/env

# First deploy (manual)
git tag v1.0.0 && git push --tags
```

The `deploy/` directory also contains:
- `openclaw.service` — systemd unit file
- `env.example` — template for `/etc/openclaw/env`
- `vps-setup.sh` — idempotent setup script (creates user, dirs, installs service)

---

## Future Skills

The platform is built to accommodate additional skills without modifying core infrastructure. Planned additions include:

- **VPS Monitor** — resource usage alerts and uptime checks
- **Backup Agent** — scheduled database and file backups with status reporting
- **Finance Tracker** — expense ingestion and monthly summaries

Each skill lives under `skill/<name>/` and wires into the shared `OpenRouterClient`, `TelegramClient`, and security layer.

---

## Author

**Piotr Capecki** — [github.com/kika-cgun](https://github.com/kika-cgun)
