---
name: career-digest
description: Fetch compact daily career digest from Java backend with deterministic tool dispatch
argument-hint: limitStrong=5 limitMedium=5 onlyUnsent=true sinceCursor=<ISO_LOCAL_DATE_TIME>
user-invocable: true
disable-model-invocation: false
metadata: {"openclaw":{"requires":{"env":["CAREER_API_BASE_URL","CAREER_API_KEY"]},"primaryEnv":"CAREER_API_KEY"}}
---

Use this skill for daily compact digest pulls from the Career backend.

When invoked by user command, call tool fetch_career_digest.
Parse optional key=value arguments from the user input and pass them as tool parameters:
- limitStrong
- limitMedium
- onlyUnsent
- sinceCursor

Command arguments (raw key=value format):
- limitStrong=<number>
- limitMedium=<number>
- onlyUnsent=<true|false>
- sinceCursor=<ISO_LOCAL_DATE_TIME>

Example:
/career-digest limitStrong=5 limitMedium=5 onlyUnsent=true
