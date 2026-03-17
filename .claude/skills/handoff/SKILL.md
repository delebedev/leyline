---
name: handoff
description: Session end — write notes about what was done, what's next, and any blockers to docs/SESSION.md for the next conversation to pick up.
---

## What I do

Snapshot current session context so `/pickup` has continuity. Can run mid-session as a checkpoint — overwrites each time.

## Steps

1. Review what happened (git log since session start, conversation context, decisions made)
2. Note any open threads worth picking up
3. Write to `docs/SESSION.md` (always overwrite — latest session only)
4. If conversation produced durable learnings (gotchas, decisions, workflow patterns), save to memory too

## Output format

Write `docs/SESSION.md`:

```markdown
# Last Session

**Date:** <today>
**Branch:** <current branch>

## What happened
- <commits, decisions, discoveries, tooling changes — whatever matters>

## Changed
- <files/tools/skills added or modified, with one-line context>

## Open threads
- <anything worth picking up — unfinished work, ideas discussed, things to verify>
```

Keep it short — 15-20 lines max. This is a breadcrumb, not a report. If it doesn't fit in 20 lines, you're writing too much.

## Key points

- **Overwrite, don't append.** SESSION.md is always the latest session.
- **History is git log.** Don't duplicate it.
- **Checkpoint, not goodbye.** User may keep working after handoff.
- **Memory for durable stuff.** SESSION.md is ephemeral (next session overwrites). If something should survive multiple sessions, save it to memory instead.
