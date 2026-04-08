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

- CAREER_API_BASE_URL (example: <http://127.0.0.1:18080> for Docker host mapping)
- CAREER_API_KEY
- Optional: CAREER_API_TIMEOUT_MS (default: 10000)
- Optional: CAREER_API_RETRY_ATTEMPTS (default: 2)
- Optional: CAREER_API_RETRY_BASE_DELAY_MS (default: 350)

## Recommended OpenClaw config

Set plugin skill env in `~/.openclaw/openclaw.json`:

```json
{
	"plugins": {
		"entries": {
			"career-digest": { "enabled": true }
		}
	},
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

## Install locally in OpenClaw

1. openclaw plugins install ./openclaw/career-digest-plugin
2. openclaw plugins enable career-digest
3. openclaw gateway restart

## Example usage

- /career-digest limitStrong=5 limitMedium=5 onlyUnsent=true
- /career-digest-ack offerIds=<uuid1,uuid2>
- /career-digest-run limitStrong=5 limitMedium=5 onlyUnsent=true autoAck=true
