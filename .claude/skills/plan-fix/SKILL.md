---
name: plan-fix
description: Turn a diagnosis artifact into a concrete implementation plan. Human gate — stops for approval before any code changes.
---

## What I do

Take a structured diagnosis (from the diagnose skill) and produce a concrete, reviewable implementation plan. This is the human gate — nothing gets implemented until the plan is approved.

## When to use me

- After `diagnose` produces a root cause + suggested approach
- "plan a fix for #37"
- As phase 3 of the dev-loop

## Input

A diagnosis artifact containing:
- Root cause (module/file/function + explanation)
- Blast radius assessment
- Suggested approach (high-level)

Can come from: diagnose skill output, issue comment, or conversation context.

## Process

### 1. Validate the diagnosis

Before planning, verify the diagnosis holds:
- Read the identified source files. Does the root cause make sense?
- Check if the suggested approach is feasible without side effects.
- If diagnosis seems wrong or incomplete, go back to diagnose — don't plan on shaky ground.

### 2. Scope the fix

Determine what needs to change:
- **Code changes:** which files, which functions, what specifically changes
- **Test additions:** regression test for the bug + any gap exposed by the diagnosis
- **Catalog/docs updates:** update `docs/catalog.yaml` if mechanic support changes

Apply YAGNI ruthlessly. Fix the bug, add the test, nothing more.

### 3. Write the plan

```markdown
## Fix Plan: #N — <title>

### Changes

1. **<file:function>** — <what changes and why>
2. **<file:function>** — <what changes and why>
3. ...

### Tests

1. **<test file>** — <test description> (regression for the reported bug)
2. ...

### Verification

- [ ] Unit/integration test passes
- [ ] Reproduce steps no longer trigger the bug (in-game verify)
- [ ] No new client errors during verification game
- [ ] `just check` passes (module-scoped)

### Risk

- <anything that could go wrong>
- <related functionality to spot-check>
```

### 4. STOP — present for review

Present the plan to the human. Do NOT proceed to implementation until approved.

Format the presentation as:
- Summary (1-2 sentences: what's broken, what the fix is)
- The plan above
- Explicit ask: "Approve this plan to proceed with implementation?"

### 5. On approval

Invoke existing skills for implementation:
- Use **TDD** (test first, then implementation)
- Use **executing-plans** if the plan has multiple independent steps
- After implementation, trigger **reproduce** as verification

## Key conventions

- **Human gate is non-negotiable.** Even if the fix is obvious and one-line, present the plan.
- **Scope to the bug.** Don't refactor surrounding code, add docs to untouched functions, or "improve" things along the way.
- **One issue per plan.** If diagnosis reveals multiple issues, file separate issues and plan separately.
- **Link everything.** Plan references diagnosis, diagnosis references issue, implementation references plan.
