---
name: pickup
description: Session start briefing — recent commits, board state, open issues, current branch context. Run at the start of every conversation.
---

## What I do

Generate a concise briefing of where the project stands so we can pick up where we left off.

## Steps

Run these in parallel, then synthesize a briefing:

1. **Recent git activity:**
   ```bash
   git log --oneline -15
   ```

2. **Current branch + uncommitted work:**
   ```bash
   git status --short
   git branch --show-current
   ```

3. **Open GitHub issues (bugs + epics):**
   ```bash
   gh issue list -R delebedev/leyline --limit 20 --state open
   ```

4. **Session notes** (if they exist):
   ```bash
   cat docs/SESSION.md 2>/dev/null
   ```

## Output format

Present a briefing like this:

```
## Session Briefing

**Branch:** <current branch>
**Uncommitted:** <summary or "clean">

### Recent (last 5-7 notable commits)
- <commit summaries>

### Open epics
- <epic titles + status>

### Open bugs (top 5 by recency)
- <bug titles>

### Last session notes
<from SESSION.md if exists, otherwise "none">

### Suggested focus
<based on what's in progress, what's blocked, what's next on the board>
```

Keep it to ~20 lines. Don't read code — just orient.
