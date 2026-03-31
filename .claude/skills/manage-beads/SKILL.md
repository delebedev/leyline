---
name: manage-beads
description: Use when creating, updating, searching, or syncing beads issues. Also use for beads troubleshooting — sync failures, identity mismatch, worktree errors, or pull/push problems.
---

## Overview

`bd` (beads) is the issue tracker for agent work. Dolt-backed, git-synced across machines. GH issues remain for public-facing bugs only.

## Creating Issues

Every issue needs: title, description, type, priority, and labels.

```bash
bd create \
  --title="Short actionable title" \
  --description="Problem statement. Root cause. Fix approach. Evidence refs." \
  --type=bug|feature|task|chore|epic \
  --priority=2 \
  --external-ref="gh-NNN" \
  --acceptance="Concrete done criteria." \
  --notes="Context: PRs, card specs, related work."
```

**After creating**, always add labels:
```bash
bd tag <id> <label>
```

### Labels

**Workflow:** `ready` (spec'd, agent can pick up), `needs-spec` (idea, not actionable yet)

**Area:** `protocol`, `conformance`, `mechanic`, `tooling`, `docs`, `refactor`

### Linking

```bash
bd dep add <child> <parent>     # hard block — child hidden from bd ready until parent closes
bd dep relate <A> <B>           # soft link — "these are related", no blocking
bd duplicate <id> --of=<other>  # dedup
bd supersede <id> --with=<new>  # replacement
```

Use `relates_to` for sibling issues in the same area (e.g. two crew bugs). Use `dep add` for real blockers.

### Migrating from GH Issues

1. `gh issue view NNN --json title,body,comments` — get full content
2. Create beads issue with description, acceptance, external-ref
3. Replace ephemeral refs (branch names) with persistent ones (PR numbers, card spec names)
4. Drop recording paths — use "Player.log data available" instead
5. `gh issue close NNN --comment "Moved to beads: leyline-xxx"`

## Searching

```bash
bd list                              # all open
bd list --label ready                # agent-actionable
bd ready                             # unblocked + ready
bd search "keyword"                  # title search
bd query 'description=JaCoCo'        # body text search
bd query 'label=tooling AND priority>=2'  # compound filter
bd query 'type=bug AND status=open'  # by type
```

`bd search` is title-only. `bd query` is the power tool — supports `description=`, `notes=`, `title=`, `label=`, `priority`, `status`, `type`, `assignee`, plus `AND`/`OR`/`NOT`.

## Working on Issues

```bash
bd ready                    # pick work
bd show <id>                # review details + deps
bd update <id> --claim      # claim — sets assignee + in_progress
# ... do the work ...
bd close <id>               # done
bd close <id> --suggest-next  # shows newly unblocked issues
```

## Sync (Multi-Machine)

**Critical config** — must be set on every machine:
```bash
bd config set dolt.auto-commit on    # commits after every write — required for clean pull/push
```

Without this, pulls fail with "cannot merge with uncommitted changes".

**Sync workflow:**
```bash
bd dolt pull        # always pull first
bd dolt push        # then push
```

**NEVER use `bd dolt push --force`** — it overwrites the remote and destroys other machines' data.

### If pull fails with "uncommitted changes"

Auto-commit wasn't on when writes happened. Fix:
```bash
bd sql 'CALL dolt_add("-A")'
bd sql 'CALL dolt_commit("-am", "commit working set")'
bd dolt pull
```

### If pull fails with "non-fast-forward" on push

Other machine pushed first. Pull, then push:
```bash
bd dolt pull
bd dolt push
```

### Identity mismatch error

```
PROJECT IDENTITY MISMATCH — metadata.json project_id != database _project_id
```

The `.beads/metadata.json` (git-tracked) has a different project_id than the Dolt DB. Fix: update metadata.json to match the DB:
```bash
bd sql 'SELECT * FROM metadata WHERE `key` = "_project_id"'
# Edit .beads/metadata.json to match, commit to git
```

### Nuclear recovery — reset to remote

If local state is corrupted beyond repair:
```bash
bd sql 'CALL dolt_fetch("origin")'
bd sql 'CALL dolt_reset("--hard", "origin/main")'
bd dolt pull
```

### Recovering lost issues from Dolt history

```bash
bd sql 'SELECT * FROM dolt_log ORDER BY date DESC LIMIT 20'
bd sql 'SELECT id, title FROM issues AS OF "<commit_hash>" WHERE id = "leyline-xxx"'
# Re-insert if missing from current state:
bd sql 'INSERT INTO issues SELECT * FROM issues AS OF "<commit_hash>" WHERE id = "leyline-xxx"'
```

## Worktrees

`bd` does NOT work from worktrees (server discovery bug — starts a new empty server instead of finding main repo's). Run `bd` from the main repo only.

## Memories

```bash
bd remember "insight" --key short-key   # store
bd memories                             # list all
bd memories <keyword>                   # search
bd forget <key>                         # delete
bd prime                                # full context dump including memories
```

Note: memories don't sync across machines (stored in config table, merge doesn't propagate). Per-machine only for now.

## Priority Reference

| Priority | Meaning | Example |
|----------|---------|---------|
| P0 | Critical blocker | Server won't start |
| P1 | High — blocks v0.1 or horizontal | Trample overflow, Brawl gameplay |
| P2 | Medium — important but not blocking | CI coverage, arena-ts features |
| P3 | Low — cosmetic or isolated | Crew prompt text, annotation conformance |
| P4 | Backlog — someday | CardIdentity refactor, deferred items |
