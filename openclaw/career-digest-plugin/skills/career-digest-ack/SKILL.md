---
name: career-digest-ack
description: Acknowledge delivered offer IDs in Java backend so digest does not repeat them
argument-hint: offerIds=<uuid1,uuid2,...>
user-invocable: true
disable-model-invocation: false
metadata: {"openclaw":{"requires":{"env":["CAREER_API_BASE_URL","CAREER_API_KEY"]},"primaryEnv":"CAREER_API_KEY"}}
---

Use this skill after delivery confirmation to mark offers as sent.

When invoked by user command, call tool ack_career_digest.
Pass parsed offerIds as an array parameter.

Required raw argument:
- offerIds=<uuid1,uuid2,...>

Example:
/career-digest-ack offerIds=11111111-1111-1111-1111-111111111111,22222222-2222-2222-2222-222222222222
