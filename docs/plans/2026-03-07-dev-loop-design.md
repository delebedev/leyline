# Autonomous Dev Loop — Design

Composable skill chain for gameplay bug resolution: pick issue, reproduce in-game, diagnose via debug API + code, plan fix (human gate), implement, verify.

## Scope

**V1: gameplay/protocol bugs only** (#30-#42 style). Tooling and account-server issues use standard workflows.

## Architecture

```
                    ┌─────────────┐
                    │  dev-loop    │  orchestrator (thin)
                    └──────┬──────┘
           ┌───────────────┼───────────────┐
           v               v               v
    ┌─────────────┐ ┌─────────────┐ ┌─────────────┐
    │  reproduce   │ │  diagnose    │ │  plan-fix    │
    └─────────────┘ └─────────────┘ └─────────────┘
           │               │               │
    arena-automation  debug-server    writing-plans
    rule              skill           skill
```

Each skill is standalone — invocable independently or chained by the orchestrator.

## Phase: reproduce

**Input:** GitHub issue (number or URL)

**Process:**
1. Read issue body. Extract `## Reproduction` section if present.
2. Classify: has-recipe vs exploratory.
3. **Has-recipe:** follow steps — launch arena, navigate, execute actions, monitor.
4. **Exploratory:** play autopilot, monitor debug API + client-errors for failure pattern matching issue description.
5. Capture failure evidence: debug API state snapshot, client errors, log excerpts.

**Output:** reproduction report (success/fail, evidence collected, session recording path).

**Relies on:** arena-automation rule, debug-server skill.

## Phase: diagnose

**Input:** reproduction evidence (or manual trigger with issue context)

**Process:**
1. Debug API triage (escalation ladder from debug-server skill).
2. Client-side crash check (Player.log).
3. Code tracing — from error/anomaly back to source. Use debug API's id-map, state-diff, priority-events to identify the failing code path.
4. Read relevant source files, understand the bug.
5. Check blast radius — what else uses this code path, related issues.

**Output:** structured diagnosis artifact:

```markdown
## Diagnosis: #N — title

### Failure observed
- What happened (debug API state, client error, log line)
- Expected vs actual

### Root cause
- Module/file/function
- Why it fails
- Evidence (log excerpts, state diffs, code references)

### Blast radius
- What else touches this code path
- Related issues

### Suggested approach
- High-level fix direction
```

**Relies on:** debug-server skill, codebase exploration.

## Phase: plan-fix

**Input:** diagnosis artifact

**Process:**
1. Turn diagnosis into concrete code changes + test additions.
2. Write implementation plan (files to change, what to change, test strategy).
3. **STOP — human review gate.** Present plan, wait for approval.

**Output:** implementation plan (compatible with writing-plans/executing-plans skills).

**Relies on:** writing-plans skill.

## Phase: implement + verify

No dedicated skills. Implementation uses existing TDD / executing-plans. Verification re-runs the reproduce phase and confirms the failure is gone + no new client errors.

## Orchestrator: dev-loop

Thin chain:
1. Pick issue (from arg or next bug-labeled issue).
2. Run `reproduce`.
3. Run `diagnose`.
4. Run `plan-fix` — **pause for human approval.**
5. On approval: implement (TDD), then `reproduce` again as verification.
6. If verified: commit, update issue.

Can be interrupted at any phase. Re-entering resumes from last phase.

## Skill locations

```
.claude/skills/diagnose/SKILL.md
.claude/skills/reproduce/SKILL.md
.claude/skills/plan-fix/SKILL.md
.claude/skills/dev-loop/SKILL.md
```

## Build order

1. `diagnose` — most value, debug API already works
2. `reproduce` — arena automation exists, needs issue-awareness
3. `plan-fix` — diagnosis to plan, human gate
4. `dev-loop` — wire the chain

## Issue conventions

Gameplay bugs should include when possible:

```markdown
## Reproduction
1. Deck: <deck name or "any">
2. Steps: <what to do in-game>
3. Expected: <what should happen>
4. Actual: <what happens instead>
```

Not required — exploratory mode handles issues without recipes.
