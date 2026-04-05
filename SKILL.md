# OpenClaw — Career Skill

OpenClaw is a self-hosted AI agent that automatically scrapes Polish IT job boards,
scores offers against your profile using an LLM, and sends a daily digest to Telegram.

---

## What This Skill Does

1. **Scrapes** job offers from 6 sources (JustJoinIT, NoFluffJobs, Bulldogjob, Pracuj.pl, Amazon Jobs, JIT.team)
2. **Deduplicates** by URL hash — no repeated offers
3. **Scores** each offer with an LLM (STRONG / MEDIUM / SKIP) against your user profile
4. **Notifies** via Telegram every day at 08:00 with the best matches

---

## REST API

All endpoints require the `X-API-Key` header.

### Scraping

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/career/scrape/run` | Trigger full pipeline now (scrape + score + Telegram) |
| `GET` | `/api/career/scrape/runs` | View scrape run history |

### Offers

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/career/offers` | List all scraped offers (newest first) |
| `GET` | `/api/career/offers/{id}` | Get single offer by UUID |

### User Profile

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/career/profile` | Get your profile |
| `PATCH` | `/api/career/profile` | Update your profile |

#### Profile fields (JSON body for PATCH)
```json
{
  "stack":       ["Java", "Spring Boot", "PostgreSQL"],
  "level":       ["junior", "intern"],
  "locations":   ["Gdańsk", "Gdynia", "remote"],
  "preferences": "Preferuję projekty produktowe, praca zdalna lub Trójmiasto"
}
```

### Swagger UI
Available at: `http://localhost:8080/swagger-ui.html`

---

## Scheduler

The daily pipeline runs automatically at **08:00** every day:
```
scrape all sources → score pending offers → send Telegram digest
```

To trigger it manually:
```bash
curl -X POST http://localhost:8080/api/career/scrape/run \
  -H "X-API-Key: <your-key>"
```

---

## Sources

| Source | Method | Filter |
|--------|--------|--------|
| JustJoinIT | JSON API | Java, junior/intern, Trójmiasto or remote |
| NoFluffJobs | JSON API | Java, junior/intern, Trójmiasto or remote |
| Bulldogjob | JSON feed | Java, junior/intern, Trójmiasto or remote |
| Pracuj.pl | JSON API | Java, junior/intern |
| Amazon Jobs | Official JSON API | Java, entry-level, Poland |
| JIT.team | HTML (SPA fallback) | All (graceful empty when no active offers) |

---

## Scores

| Score | Meaning |
|-------|---------|
| `STRONG` | Great match — sent via Telegram |
| `MEDIUM` | Decent match — sent via Telegram |
| `SKIP` | Poor match — ignored |
| `PENDING_SCORE` | Not yet scored |

---

## Configuration

All configuration via environment variables (see `.env.example`):

| Variable | Description | Default |
|----------|-------------|---------|
| `DB_USERNAME` | PostgreSQL user | `openclaw` |
| `DB_PASSWORD` | PostgreSQL password | — |
| `OPENROUTER_API_KEY` | OpenRouter API key | — |
| `OPENROUTER_MODEL` | LLM model ID | `stepfun/step-3-5-flash:free` |
| `TELEGRAM_BOT_TOKEN` | Telegram bot token | — |
| `TELEGRAM_CHAT_ID` | Telegram chat/channel ID | — |
| `APP_API_KEY` | REST API key | — |

### Recommended free models (OpenRouter)

```
stepfun/step-3-5-flash:free    # default — fast, good JSON output
qwen/qwen3-235b-a22b:free      # Qwen 3.6 Plus — better reasoning
qwen/qwen3-30b-a3b:free        # lighter, faster
anthropic/claude-sonnet-4-5    # paid, best quality
```

---

## How to Run

### Option A — Docker Compose (recommended)

```bash
# 1. Copy and fill in secrets
cp .env.example .env
# edit .env: DB_PASSWORD, OPENROUTER_API_KEY, TELEGRAM_*, APP_API_KEY

# 2. Start
docker compose up -d

# 3. Create your profile (once)
curl -X PATCH http://localhost:8080/api/career/profile \
  -H "X-API-Key: <your-key>" \
  -H "Content-Type: application/json" \
  -d '{
    "stack": ["Java", "Spring Boot", "PostgreSQL"],
    "level": ["junior", "intern"],
    "locations": ["Gdańsk", "remote"],
    "preferences": "Preferuję projekty produktowe, zdalnie lub Trójmiasto"
  }'

# 4. Trigger first scrape manually
curl -X POST http://localhost:8080/api/career/scrape/run \
  -H "X-API-Key: <your-key>"
```

### Option B — Local (dev)

Requirements: Java 21, PostgreSQL 16 running locally.

```bash
# 1. Set env vars
export DB_USERNAME=openclaw
export DB_PASSWORD=openclaw
export OPENROUTER_API_KEY=sk-or-...
export OPENROUTER_MODEL=stepfun/step-3-5-flash:free
export TELEGRAM_BOT_TOKEN=123456789:AAF...
export TELEGRAM_CHAT_ID=-100123456789
export APP_API_KEY=$(openssl rand -hex 32)

# 2. Run
./gradlew bootRun
```

### Option C — VPS (production)

```bash
# One-time setup on fresh Debian/Ubuntu VPS (run as root):
bash deploy/vps-setup.sh

# Edit secrets:
nano /etc/openclaw/env

# Deploy by pushing a git tag:
git tag v1.0.0 && git push --tags
# GitHub Actions builds the JAR and deploys via SSH (see .github/workflows/cd.yml)
```

---

## Tech Stack

- **Java 21** + **Spring Boot 4**
- **PostgreSQL 16** + Flyway migrations
- **OkHttp** for HTTP scraping
- **Jackson** for JSON parsing
- **OpenRouter** for LLM scoring (any OpenAI-compatible model)
- **Telegram Bot API** for notifications
- **Springdoc OpenAPI** for Swagger UI
