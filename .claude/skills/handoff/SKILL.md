---
name: handoff
description: Session end — write notes about what was done, what's next, and any blockers to docs/SESSION.md for the next conversation to pick up.
---

## What I do

Capture session context so the next `/pickup` has continuity.

## Steps

1. Summarize what happened this session (commits, decisions, discoveries)
2. Note what's in progress or unfinished
3. Note blockers or open questions
4. Write to `docs/SESSION.md` (overwrite — this is always "latest session")

## Output format

Write `docs/SESSION.md` like this:

```markdown
# Last Session

**Date:** <today>
**Branch:** <current branch>

## Done
- <what was accomplished, with issue/PR numbers if relevant>

## In progress
- <what's started but not finished>

## Next
- <suggested next steps>

## Blockers / open questions
- <anything unresolved>
```

Keep it short — 10-15 lines max. This is a breadcrumb, not a report.
