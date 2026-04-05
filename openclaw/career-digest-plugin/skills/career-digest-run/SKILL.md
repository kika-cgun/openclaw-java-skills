---
name: career-digest-run
description: Run full deterministic cycle for career digest (status, fetch, optional ack)
argument-hint: limitStrong=5 limitMedium=5 onlyUnsent=true autoAck=true sinceCursor=<ISO_LOCAL_DATE_TIME>
user-invocable: true
disable-model-invocation: false
metadata:
  {
    "openclaw":
      {
        "requires": { "env": ["CAREER_API_BASE_URL", "CAREER_API_KEY"] },
        "primaryEnv": "CAREER_API_KEY",
      },
  }
---

Use this skill for one-command daily execution.

When invoked by user command, call tool run_career_digest_cycle.
Parse optional key=value arguments from user input and pass them as tool parameters:

- limitStrong
- limitMedium
- onlyUnsent
- sinceCursor
- autoAck

Recommended command:
/career-digest-run limitStrong=5 limitMedium=5 onlyUnsent=true autoAck=true

Tool output contains status, digest, ack, and a preformatted message field.
