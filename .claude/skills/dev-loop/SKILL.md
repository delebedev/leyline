---
name: dev-loop
description: Autonomous gameplay bug resolution loop. Chains reproduce → diagnose → plan-fix → implement → verify. Pauses at human gate before implementation.
---

## What I do

Drive a gameplay bug from issue to fix with minimal human intervention. Chains the reproduce, diagnose, and plan-fix skills, then uses TDD for implementation and re-reproduction for verification.

## When to use me

- "dev-loop #37" / "fix #37 end to end"
- "pick a bug and fix it"
- When you want the full autonomous loop

## Input

- **Issue number** (optional) — specific bug to work on
- If omitted: picks the next bug-labeled gameplay issue

## The loop

### Phase 1: Pick

```bash
# Specific issue
gh issue view <N> --json title,body,labels

# Or pick next bug
gh issue list --label bug --json number,title,labels --limit 5
```

Filter to gameplay/protocol bugs (not tooling, not account-server). Check `docs/catalog.yaml` — skip issues for mechanics marked "missing" unless the fix is to implement the mechanic.

Announce: "Working on #N — <title>"

### Phase 1b: Recording scope (for mechanic/protocol bugs)

If the bug involves a game mechanic, zone transfer, or annotation:

Invoke the **recording-scope** skill. Decode the proxy recording to see what the real server sends for this mechanic. This prevents discovering protocol gaps during debugging.

**Example:** Surveil (#66) — we spent 4 hours debugging because we didn't decode the recording first. The real server's `ObjectIdChanged` + `ZoneTransfer(category:"Surveil")` + dual-object diff pattern was visible in 15 minutes of recording analysis. See `docs/retros/2026-03-08-surveil-scoping-retro.md`.

Skip this phase for pure UI bugs, account-server issues, or FD protocol bugs that don't involve game state.

### Phase 2: Reproduce

Invoke the **reproduce** skill with the issue number.

**If REPRODUCED:** `gh issue edit <N> --add-label dl:reproduced`. Proceed to diagnose.
**If NOT_REPRODUCED after 3 attempts:**
- Check if the bug is environment-specific or needs a specific deck
- Comment on the issue with reproduction attempt details
- Pick a different issue or ask the human

### Phase 3: Diagnose

Invoke the **diagnose** skill. Use reproduction evidence + debug API + code tracing.

**For protocol/annotation bugs:** Structure the diagnosis using `docs/conformance/diagnosis-schema.md` — every claim must cite evidence (recording data, code location, engine output, tool output). Unsourced assertions about protocol behavior are not acceptable.

**Output:** structured diagnosis artifact with root cause + cited claims. `gh issue edit <N> --add-label dl:diagnosed`.

### Phase 3b: Verify Diagnosis (Gate 1)

**For protocol/annotation bugs:** Dispatch to the **conformance subagent** to independently verify each claim in the diagnosis against recording data. The diagnosing agent does not verify its own claims.

> Verify the claims in [diagnosis] against recording data at [session path].
> For each claim: check the cited evidence, confirm or correct.

**Why:** The surveil retro proved same-agent verification fails. The conformance subagent corrected a wrong root cause that the main agent was confident about.

**If claims are corrected:** Revise diagnosis before proceeding to plan.

### Phase 4: Plan (HUMAN GATE)

Invoke the **plan-fix** skill. Produces concrete implementation plan.

**STOP HERE.** Present the plan. Wait for human approval. `gh issue edit <N> --add-label dl:planned`.

Do not proceed without explicit "approved" / "go" / "lgtm" / "yes".

### Phase 5: Implement

On approval:
1. Create a feature branch: `fix/<N>-<short-description>`
2. Use **TDD**: write regression test first, see it fail, then implement the fix
3. Run module-scoped checks: `just check` for the affected module
4. Format: `just fmt`

### Phase 6: Verify (Gate 3)

**Server restart is mandatory.** If source files changed since last server start, `just stop` + `just serve` before any in-game checks. The running JVM has old bytecode.

Three layers for protocol/annotation bugs, two for others:

1. **Conformance pipeline** (protocol bugs) — `just conform <Category> [session]`. Verifies annotation structure matches recording. Exit 0 = perfect, exit 1 = known gaps, exit 2 = regression. If improved over golden, capture new baseline: `just conform-golden <Category>`.
2. **Unit/integration test** — the regression test from Phase 5 passes, proving the code change works
3. **In-game** — re-run the **reproduce** skill. For visual bugs: `arena capture` + annotated screenshot proving the visual is fixed. For protocol bugs: debug API confirms correct output. Check for new client errors.
4. Module-scoped test task or `just test-gate` passes — no regressions
5. **Before/after comparison** (visual bugs) — side-by-side annotated image using the repro screenshot as "before" and the verify screenshot as "after". Upload to R2 for issue comment.

Unit test alone can miss client-side behavior. In-game alone can miss edge cases. Conformance pipeline catches proto-level regressions that both miss. All layers together = reliable.

**If verification fails:** go back to diagnose. The fix was incomplete or wrong.

### Phase 7: Close

1. Commit with message referencing the issue: `fix(module): description (closes #N)`
2. Report: "Issue #N fixed and verified. Ready for PR when you want."

Do NOT push or create PR automatically — wait for human.

## Interruption and re-entry

The loop can be interrupted at any phase. To resume:
- "continue dev-loop" → check where we left off (look for /tmp/repro-*.json, diagnosis comments, uncommitted changes)
- "skip to diagnose" → jump directly to that phase
- "re-verify #37" → run just phase 6

## Failure modes

| Situation | Action |
|-----------|--------|
| Can't reproduce | Comment on issue, pick another bug |
| Diagnosis unclear | Ask human for hints, don't guess |
| Plan rejected | Revise based on feedback, re-present |
| Fix doesn't verify | Back to diagnose — root cause was wrong |
| Unrelated crash during reproduce | Concede, restart, note the crash as a separate issue |
| Server not running | Start it (`just serve`) or ask human |
| Arena not running | `arena launch` or ask human |

## Key conventions

- **Human gate is sacred.** No implementation without plan approval.
- **One bug at a time.** Don't context-switch mid-loop.
- **Evidence over intuition.** Every phase produces artifacts, every claim has proof.
- **Don't over-scope.** Fix the bug, test the bug, move on. File new issues for tangential problems discovered along the way.
