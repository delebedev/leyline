---
name: diagnose
description: Diagnose a gameplay bug using debug API, logs, client errors, and code tracing. Produces structured diagnosis artifact for plan-fix.
---

## What I do

Trace a gameplay/protocol bug from observable failure to root cause in code. Combines debug API inspection, client error analysis, and codebase exploration into a structured diagnosis.

## When to use me

- "diagnose #37" / "diagnose targeting bug"
- After reproducing a bug in-game and needing to understand why
- When debug server shows anomalies and you need the code path
- As phase 2 of the dev-loop

## Inputs

Either:
- **GitHub issue number** — reads issue, extracts failure description
- **Live/recent session** — works from current debug API state + recordings
- **Both** — issue provides context, debug API provides evidence

## Process

### 1. Gather context

If issue number provided:
```bash
gh issue view <N> --json title,body,labels,comments
```

Check for prior diagnosis or reproduction notes in comments.

### 2. Debug API triage

Escalate through these — stop when you find the anomaly:

```bash
# Match state
curl -s http://localhost:8090/api/state | python3 -m json.tool

# Recent protocol messages
curl -s http://localhost:8090/api/messages | python3 -m json.tool

# Warnings/errors in server logs
curl -s 'http://localhost:8090/api/logs?level=WARN' | python3 -m json.tool

# Priority events — is engine waiting on something?
curl -s http://localhost:8090/api/priority-events | python3 -m json.tool

# State diffs — what changed last?
curl -s 'http://localhost:8090/api/state-diff?last=2' | python3 -m json.tool

# Card tracking — where is the relevant card?
curl -s 'http://localhost:8090/api/id-map?active=true' | python3 -m json.tool
curl -s 'http://localhost:8090/api/instance-history?id=<N>' | python3 -m json.tool
```

If no live session, check recordings:
```bash
curl -s http://localhost:8090/api/recordings | python3 -m json.tool
curl -s 'http://localhost:8090/api/recording-summary?id=latest' | python3 -m json.tool
```

### 3. Client-side crash check

Always check — server can look fine while client crashes:

```bash
# Recent client errors from debug API
curl -s http://localhost:8090/api/client-errors | python3 -m json.tool

# Or direct from Player.log if server is down
grep -n "Exception\|Error\|NullReference" ~/Library/Logs/Wizards\ Of\ The\ Coast/MTGA/Player.log | tail -10
```

### 4. Code tracing

From the anomaly found in steps 2-3, trace to source:

- **Protocol issue** (wrong/missing field) → `matchdoor/` proto builders, game event wiring
- **State mapping issue** (wrong zone, missing annotation) → `matchdoor/game/` state mapping
- **Combat/targeting** → `matchdoor/match/` combat/targeting handlers
- **Front door issue** → `frontdoor/` wire format, domain model
- **Client crash on our message** → compare what we sent (debug API) vs what client expected (Player.log)

Use `docs/rosetta.md` for protocol type cross-reference. Use `docs/catalog.yaml` to check if the mechanic is known-unsupported.

### 5. Blast radius check

- What other code calls the same function/uses the same path?
- Are there related open issues that might share the root cause?
- Does fixing this risk breaking something that currently works?

```bash
gh issue list --label bug --json number,title
```

### 6. Write diagnosis

Output a structured artifact — either as a comment on the issue or as input to plan-fix:

```markdown
## Diagnosis: #N — <title>

### Failure observed
- <what happened — debug API state, client error, log line>
- Expected: <what should happen>
- Actual: <what happens instead>

### Root cause
- **Where:** <module/file:line>
- **Why:** <explanation — missing field, wrong mapping, unhandled case>
- **Evidence:** <log excerpts, state diffs, code references>

### Blast radius
- <what else touches this code path>
- <related issues>

### Suggested approach
- <high-level fix direction, not implementation detail>
```

## Key conventions

- **Don't guess.** Every claim in the diagnosis must have evidence (a log line, a state diff, a code reference).
- **Check catalog.yaml first.** If the mechanic is listed as "missing", that's the diagnosis — no need to trace further.
- **Prefer debug API over grep.** The API gives you structured, correlated data. Code search is for after you know where to look.
- **msgSeq correlation.** State snapshots and priority events share `msgSeq` — use it to tie protocol messages to state changes.
- **File the diagnosis.** Post as a comment on the issue (`gh issue comment <N> --body "..."`) so it persists across sessions.
