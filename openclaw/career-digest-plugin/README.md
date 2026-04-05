# OpenClaw Career Digest Plugin

This plugin connects OpenClaw with the Java Career backend using compact digest endpoints.

## Included tools

- fetch_career_digest
- ack_career_digest
- run_career_digest_cycle

## Included skills

- career-digest
- career-digest-ack
- career-digest-run

## Required environment variables

- CAREER_API_BASE_URL (example: <http://localhost:8080>)
- CAREER_API_KEY
- Optional: CAREER_API_TIMEOUT_MS (default: 10000)
- Optional: CAREER_API_RETRY_ATTEMPTS (default: 2)
- Optional: CAREER_API_RETRY_BASE_DELAY_MS (default: 350)

## Install locally in OpenClaw

1. openclaw plugins install ./openclaw/career-digest-plugin
2. openclaw plugins enable career-digest
3. openclaw gateway restart

## Example usage

- /career-digest limitStrong=5 limitMedium=5 onlyUnsent=true
- /career-digest-ack offerIds=<uuid1,uuid2>
- /career-digest-run limitStrong=5 limitMedium=5 onlyUnsent=true autoAck=true
