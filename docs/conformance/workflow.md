# Diagnose-Fix-Verify Workflow

> **Quick reference:** See [`how-we-conform.md`](how-we-conform.md) for the current tooling commands. Phase 0 ground-truth extraction now uses `just conform-proto` (raw proto) instead of `just tape segment template` (lossy JSON). The diagnostic phases and citation schema below remain valid.

Agent-friendly pipeline for protocol/annotation bugs. Every claim is citation-backed, every fix is verified against recordings, every verification has a regression gate.

## When to use

Any bug involving zone transfers, annotations, interactive prompts, priority flow, message sequencing, or proto field conformance. NOT for pure logic errors (wrong combat damage calculation, incorrect mana cost check) — those are standard test-driven fixes.

## Pipeline overview

```
Phase 0: Ground Truth       Phase 1: Diagnosis         Phase 2: Implement
──────────────────          ──────────────────         ──────────────────
Decode recording      →     Claims with citations  →   Test from template
Wire spec artifact          Each claim verifiable      Implement fix
                                   │                          │
                            ───────▼──────             ───────▼──────
                            Gate 1: Verify             Gate 3: Conform
                            Conformance subagent       just conform <cat>
                            checks claims              Golden regression
                                   │
                            ───────▼──────
                            Gate 2: Plan
                            Human approves
```

## Phase 0: Ground Truth Assembly

**Before any diagnosis.** Non-negotiable for protocol bugs.

```bash
# Find the relevant recording segment
just tape segment list [session]

# Extract templatized wire spec
just tape segment template <Category> [session] > /tmp/<mechanic>-template.json
```

Produce a wire spec (see `wire-spec-schema.md`):
- What annotations the real server sends, with detail keys
- What zone transfers occur, with categories
- What instanceId lifecycle events occur (ObjectIdChanged, realloc)
- What persistent annotations are present

**Seat choice:** Annotations are global — identical content regardless of receiving seat. Use `--seat 2` when seat 1 recording is unavailable or when the opponent's perspective has the cleaner segment. Only zone visibility differs (opponent hand is face-down). Prompts/requests (`ActionsAvailableReq`, `SelectNReq`) are seat-specific — only the acting player receives them.

**Output:** Template JSON + written observations.

**Why mandatory:** The surveil retro proved that scoping from engine behavior ("how does Forge do surveil?") instead of wire protocol ("what does the real server send?") wastes hours. 4 hours debugging vs 15 minutes of recording decode.

## Phase 1: Diagnosis with Citations

Analyze the bug against ground truth. Every factual claim must have a citation.

**Citation types:**

| Type | Format | Example |
|---|---|---|
| recording | `recording:<session>,gs=<N>,<path>` | `recording:CHALLENGE-STARTER,gs=247,annotations[1].details.category` |
| code | `code:<file>:<line>` | `code:StateMapper.kt:142` |
| engine | `engine:<output-file>,<path>` | `engine:build/conformance/playland-frame.json` |
| test | `test:<TestClass>:<line>` | `test:CategoryFromEventsTest:45` |
| tool | `tool:<command>` | `tool:just proto-annotation-variance --summary` |

**Output:** Structured diagnosis (see `diagnosis-schema.md`) with root cause, cited claims, blast radius, test gap.

## Gate 1: Independent Verification

Dispatch to conformance subagent:

> Verify the claims in [diagnosis] against recording data at [session path].
> For each claim: check the cited evidence, confirm or correct.
> Report verification status per claim.

The conformance subagent reads the recording independently. It has no context from the diagnosis except the claims and citations.

**Why independent:** The diagnosing agent was wrong about surveil's root cause. It said `detectZoneTransfers` can't see the card; the subagent showed it CAN — the real gap was the category label. Same-agent verification misses this class of error.

**Output:** Per-claim `verified: true/false` with corrections if any.

## Gate 2: Plan + Human Approval

Human sees:
- Verified diagnosis (claims + verification status)
- Proposed implementation plan
- Test plan derived from recording template

Human approves, redirects, or asks for more investigation.

## Phase 2: Test-First Implementation

1. **Template exists** from Phase 0 — the recording segment for this interaction
2. **Write failing test** — load template, run same interaction through engine, diff
3. **See it fail** — the diff shows exactly what's wrong
4. **Implement fix** — guided by specific diff failures
5. **See it pass** — annotations match

## Gate 3: Conformance + Regression Check

```bash
# Run full pipeline: engine test → template → diff (+ golden check)
just conform <Category> [session]

# If improved, capture new golden baseline
just conform-golden <Category> [session]
```

**Exit codes:**
- 0 = perfect match (all annotations + persistent annotations match)
- 1 = known diffs exist (documented in golden file)
- 2 = regression — more diffs than golden baseline (blocks merge)

**Golden ratchet:** Golden files in `matchdoor/src/test/resources/golden/conform-*.json` document the current conformance state. Conformance can only improve (fewer diffs), never regress (more diffs).

## Agent roles

| Agent | Role | Permissions |
|---|---|---|
| Main agent | Phases 0-2, implementation | Full code access |
| Conformance subagent | Gate 1 verification | Read-only: recordings, code, engine output |
| Human | Gate 2 approval | Approves plan before implementation |

Two agents is correct. The conformance subagent exists (`.claude/agents/conformance.md`). Adding more agents adds coordination overhead without proportional verification value.

## Anti-patterns

1. **Scoping from engine behavior.** Ask "what does the real server send?" before "how does Forge do this?" Recording is the spec.

2. **Uncited claims.** "The server sends X" without `[citation: recording:...]` is an assertion, not a fact. Gate 1 can't verify what it can't check.

3. **Same-agent verification.** The agent that diagnosed the bug should not verify its own diagnosis. That's what Gate 1 is for.

4. **Playtesting instead of pipeline.** 30 minutes of playtesting produces no regression gate. `just conform` does. Playtest only for genuinely new client-visible interaction patterns (per `docs/decisions/testing-strategy.md`).

5. **Skipping Phase 0.** "I'll just read the code and figure it out" — the surveil retro disproved this. The recording already has the answer.

6. **No golden capture after fix.** The fix works, but nobody runs `just conform-golden` to update the baseline. Next change can silently regress.

## Quick reference

| Step | Command |
|---|---|
| List segments | `just tape segment list [session]` |
| Extract wire spec | `just tape segment template <Category> [session]` |
| Run full pipeline | `just conform <Category> [session]` |
| Capture golden | `just conform-golden <Category> [session]` |
| Check variance | `just tape annotation variance` |
| Current goldens | `matchdoor/src/test/resources/golden/conform-*.json` |

## Related docs

- `pipeline.md` — conformance pipeline mechanics (6 steps, comparison layers)
- `wire-spec-schema.md` — Phase 0 artifact format
- `diagnosis-schema.md` — Phase 1 structured diagnosis with citations
- `debugging.md` — annotation triage checklist
- `levers.md` — architectural improvements to make the pipeline self-sustaining
- `docs/decisions/testing-strategy.md` — pipeline over playtesting principle
- `docs/retros/2026-03-08-surveil-scoping-retro.md` — the case study that motivated this workflow
