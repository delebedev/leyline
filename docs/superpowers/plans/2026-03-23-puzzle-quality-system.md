# Puzzle Quality System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Writer-judge loop that produces high-quality `.pzl` puzzles with design narratives and structured scoring.

**Architecture:** Two new artifacts — a puzzle-judge subagent (`.claude/agents/puzzle-judge.md`) and an upgraded write-puzzle skill (`.claude/skills/write-puzzle/SKILL.md`). The judge scores puzzles on 5 dimensions (Focus, Determinism, Signal, Minimality, Documentation) and the writer auto-dispatches the judge, revising up to 3 rounds. A findings log (`docs/puzzle-findings.md`) accumulates telemetry.

**Tech Stack:** Claude Code skills/agents (markdown), no code changes.

**Spec:** `docs/superpowers/specs/2026-03-23-puzzle-quality-system-design.md`

---

### Task 1: Create puzzle-judge subagent

**Files:**
- Create: `.claude/agents/puzzle-judge.md`

- [ ] **Step 1: Write the agent definition**

The agent needs: frontmatter (name, description, model, tools), required reading instructions, the 5-dimension scoring rubric, context-aware evaluation rules, and structured output format.

Key design decisions:
- Model: `sonnet` (judge doesn't need opus-level reasoning, just rubric application)
- Tools: `Read, Grep, Glob` (read-only — judge never writes)
- Must read `docs/puzzle-design.md` and `docs/puzzles.md` before evaluating
- Evaluates fitness for the puzzle's stated purpose (Description field), not abstract quality
- Paired puzzles: when `# Paired with:` is present, evaluate Focus and Signal across the pair

```markdown
---
name: puzzle-judge
description: Evaluate .pzl puzzle quality — scores on 5 dimensions (Focus, Determinism, Signal, Minimality, Documentation), returns structured verdict. Read-only.
model: sonnet
tools: Read, Grep, Glob
memory: project
---

You evaluate `.pzl` puzzle files for quality. You are a judge, not a fixer — you score and explain, never edit.

## Before evaluating

Read these files first:
- `docs/puzzle-design.md` — design principles, AI behavior traps, templates
- `docs/puzzles.md` — architecture, goal types, format reference

## Evaluation

You receive a `.pzl` file (with optional `#` comment narrative). Evaluate it against the rubric below.

**Context-aware:** Read the puzzle's `Description:` field. Evaluate whether the design serves that stated purpose. A multi-keyword stress test that violates "one win path" is fine *if described as such*. A single-mechanic puzzle that violates one-win-path is not.

**Paired puzzles:** If the narrative contains `# Paired with: <filename>`, read the paired puzzle too. Evaluate Focus and Signal across the pair — each individual puzzle may have looser focus because the pair provides disambiguation.

## Scoring Rubric (1-3 per dimension, 15 max)

| Dimension | 1 (weak) | 2 (adequate) | 3 (strong) |
|-----------|----------|--------------|------------|
| **Focus** | Multiple win paths, unclear what's tested | One main path but alternatives exist | Single win path, mechanic is the only way |
| **Determinism** | AI has unforced choices with multiple valid plays (e.g., block assignment options, optional casts) | Life totals guide AI toward one line but edge cases exist (e.g., AI *should* attack but might not if it evaluates trades individually) | AI has zero decision points OR all decisions forced by rules text (must attack) / exact lethal math |
| **Signal** | WIN/LOSE ambiguous | Most failure modes distinguishable | Every outcome maps to a specific diagnosis |
| **Minimality** | >8 non-land cards or obvious dead weight | 5-8 non-land cards, mostly justified | <5 non-land cards, every card has a stated job, nothing removable |
| **Documentation** | No narrative or vague description | Narrative present but missing sections | All 6 narrative sections: Mechanic, Forcing, AI behavior, Failure modes, Card roles, Protocol path |

## Verdicts

- **PASS** (12-15) — puzzle is ready
- **NEEDS_REVISION** (8-11) — list specific issues, writer revises
- **REJECT** (1-7) — fundamentally flawed, explain the core problem

## Output format

Always use this exact format:

```
Score: N/15 (Focus:N Determinism:N Signal:N Minimality:N Documentation:N)
Verdict: PASS | NEEDS_REVISION | REJECT

Strengths:
- <what's well-done, 2-3 bullets>

Issues:
- <Dimension: specific problem and why it matters>

Suggestion:
- <concrete fix for each issue, if NEEDS_REVISION or REJECT>
```

## What you DON'T judge

- Card name validity — `just puzzle-check` handles that
- Syntax/formatting — the writer skill handles that
- Whether the mechanic is worth testing — that's a product decision
- Code quality of test assertions — that's a code review concern
```

- [ ] **Step 2: Verify agent is loadable**

Run: `ls -la .claude/agents/puzzle-judge.md`
Expected: file exists with correct content

- [ ] **Step 3: Commit**

```bash
git add .claude/agents/puzzle-judge.md
git commit -m "feat: add puzzle-judge subagent for puzzle quality scoring"
```

---

### Task 2: Upgrade write-puzzle skill

**Files:**
- Modify: `.claude/skills/write-puzzle/SKILL.md`

- [ ] **Step 1: Rewrite the skill with reasoning chain and judge loop**

The upgraded skill keeps all existing format/syntax rules but adds:
- 7-step reasoning chain (from spec) as the primary workflow
- Narrative comment format (`#` comments with 6 required sections)
- Auto-dispatch of puzzle-judge subagent after producing the puzzle
- 3-round revision loop with REJECT recovery
- Findings log append on completion
- Full modifier reference (the 30+ pipe-delimited modifiers from Forge)

The skill should be structured as:
1. **Reasoning chain** (the thinking process — most important section)
2. **Narrative format** (what to embed as `#` comments)
3. **File format reference** (existing content, expanded with modifiers)
4. **Judge loop** (how to dispatch judge and handle verdicts)
5. **Findings log** (how to append results)
6. **Validation** (existing `just puzzle-check` step)

Replace the entire SKILL.md with the new version:

```markdown
---
name: write-puzzle
description: Write a Forge .pzl puzzle file for a specific game scenario. Structured reasoning → narrative → judge review → findings log.
---

## What I do

Design and write a `.pzl` puzzle file that tests a specific game mechanic. The puzzle comes with a design narrative explaining WHY the board state works, not just WHAT it contains. A judge agent scores the puzzle before it's finalized.

## When to use me

- "write a puzzle that tests first strike"
- "I need a board state where blocking is the only way to survive"
- "set up a scenario for kicker testing"
- As part of Tier 1/2 reproduction in the dev-loop
- When a mechanic needs acceptance criteria

## Reference

- Design guide: `docs/puzzle-design.md` (read this first — AI behavior traps, forcing patterns, templates)
- Architecture: `docs/puzzles.md` (goal types, format, card registration)
- Existing puzzles: `matchdoor/src/test/resources/puzzles/` and `puzzles/`

## Step 1: Reasoning Chain

Before writing any `.pzl` content, think through these 7 steps. Write your reasoning out — this becomes the narrative.

### 1. Identify the mechanic
What EXACTLY is being tested? Not "combat" but "first strike damage assignment in the first combat damage step." Precision here prevents vague puzzles.

### 2. Find the forcing function
What board state makes this mechanic the ONLY path to victory?
- Testing a spell? Give AI a blocker so combat can't win.
- Testing combat? Don't give burn spells.
- Testing an ability? Make attacking suicidal.
- Testing an SBA? Block all other win paths, make the SBA the bottleneck (see legend-rule pattern in design guide).

### 3. Choose deterministic AI behavior
AI is heuristic-based — it evaluates trades individually, not collectively.
- **Forced attack:** `Juggernaut` (must attack regardless of board state)
- **Life-total forcing:** HumanLife=3 with AI having 3+ power creatures → AI always attacks
- **Defensive casting:** AI always casts to survive, rarely casts offensively when already winning
- **No AI hand cards** unless the test requires AI to cast something. AI hand is unreliable.
- **Zero decision points** is ideal: one creature, one obvious play, no blocking assignments

### 4. Map failure modes
For each outcome, what does it diagnose?
- **WIN** = mechanic works as intended
- **LOSE (human died)** = mechanic broken (damage/targeting/resolution failed)
- **LOSE (turn limit)** = required action never happened (AI didn't attack, effect didn't trigger)
- **TIMEOUT (no game over)** = engine hang (prompt/priority wiring broken)
- **CRASH** = card missing from DB, unhandled type

If two failure modes are indistinguishable, redesign the puzzle. Each outcome should tell you something different.

### 5. Minimize cards
Every card must have a stated job. Test yourself: can you remove this card and the puzzle still tests the mechanic? If yes, remove it.
- No lands if you don't need mana
- 1 library card per player minimum (draw step)
- Basic lands as library filler
- Target: <5 non-land cards

### 6. Set exact life totals
Life is a forcing function, not flavor.
- `AILife=3` + Lightning Bolt = exact lethal
- `HumanLife=5` + AI has 5-power creature = must block or die
- Exact lethal prevents alternative win paths

### 7. Trace protocol path
Check `docs/catalog.yaml` for the mechanic's status. What message types will this exercise?
- If the path touches something marked `missing` — note it. The puzzle becomes an acceptance test.
- If the path is `wired` — the puzzle should pass end-to-end.

## Step 2: Write the Narrative

Embed your reasoning as `#` comments at the top of the `.pzl` file. Six required sections:

```ini
# Design Narrative
# Mechanic: <what exactly is being tested>
# Forcing: <why this board makes the mechanic the only path>
# AI behavior: <why AI acts predictably — forced actions, life totals, no choices>
# Failure modes:
#   WIN   = <what it means>
#   LOSE  = <what it means>
#   TIMEOUT = <what it means>
# Card roles:
#   <Card Name> — <its job in this puzzle>
#   <Card Name> — <its job>
# Protocol path: <message types exercised, catalog status>
```

For paired puzzles, add: `# Paired with: <filename>`

## Step 3: Write the `.pzl` File

### Format

```ini
[metadata]
Name:<descriptive name>
Goal:<Win|Survive|Destroy Specified Creatures|Play the Specified Permanent|Gain Control of Specified Permanents|Win Before Opponent's Next Turn>
Turns:<positive integer>
Difficulty:<Tutorial|Easy|Medium|Hard>
Description:<what this tests and why — one clear sentence>

[state]
ActivePlayer=Human
ActivePhase=Main1
HumanLife=<life total>
AILife=<life total>

humanbattlefield=<semicolon-separated cards>
humanhand=<cards>
humanlibrary=<cards — minimum 1 per player>
aibattlefield=<cards>
ailibrary=<cards — minimum 1 per player>
```

### Valid Goal values
- `Win` — opponent life to 0 (or any normal win). Lose at cleanup of turn N.
- `Survive` — win at upkeep of turn N+1. Lose by normal game loss.
- `Destroy Specified Creatures` (aliases: `Destroy Specified Permanents`, `Remove Specified Permanents from the Battlefield`, `Kill Specified Creatures`) — requires `Targets:` field.
- `Play the Specified Permanent` — requires `Targets:` and optional `TargetCount:`.
- `Gain Control of Specified Permanents` — requires `Targets:`.
- `Win Before Opponent's Next Turn` — lose at opponent's upkeep.

### Zone keywords
`humanbattlefield`, `humanhand`, `humanlibrary`, `humangraveyard`, `humanexile`
`aibattlefield`, `aihand`, `ailibrary`, `aigraveyard`, `aiexile`

### Card modifiers (pipe-delimited)
Cards separated by `;`, modifiers by `|`. Format: `CardName|Modifier1|Modifier2:Value`

| Modifier | Example | Notes |
|----------|---------|-------|
| `Tapped` | `Mountain\|Tapped` | |
| `SummonSick` | `Grizzly Bears\|SummonSick` | Default is NOT sick |
| `Counters:TYPE=N` | `Hangarback Walker\|Counters:P1P1=3` | Required for planeswalkers: `\|Counters:LOYALTY=5` |
| `Transformed` | `Delver of Secrets\|Transformed` | |
| `FaceDown` | | Morph/manifest |
| `FaceDown:Manifested` | | |
| `Owner:P1` | | When controller != owner |
| `AttachedTo:ID` | | Equipment/aura (card needs `Id:N`) |
| `Id:N` | | Required for cross-references |
| `Attacking` | | Combat state |
| `NoETBTrigs` | | Skip ETB triggers on placement |
| `Set:CODE` | `Lightning Bolt\|Set:M10` | Art selection |
| `Art:N` | | Art index |

### Player state fields
| Field | Example |
|-------|---------|
| `life` | `humanlife=20` |
| `landsplayed` | `humanlandsplayed=1` |
| `counters` | `humancounters=POISON=3` |
| `manapool` | `humanmanapool=R R G` |

### Global state
| Field | Example |
|-------|---------|
| `removesummoningsickness` | `removesummoningsickness=true` |
| `turn` | `turn=3` |

### Rules for good puzzles

- **Planeswalkers on battlefield** need `|Counters:LOYALTY=N` or SBA kills them immediately. Simpler: put in hand and cast.
- **Card names must be exact Forge names.** Check `just card-grp "<name>"` if unsure.
- **Prefer simple cards** (vanilla/french vanilla) over complex ones — less noise.
- **Avoid ETB triggers** unless testing triggers.
- **ActivePlayer is always Human.** Only `Main1` is supported. To have AI act first, pass the turn.
- **Library minimum:** 1 card per player (draw step). 5+ for multi-turn puzzles.
- **HumanControl:true** — gives human control of both seats (for scripted MatchHarness runs).

## Step 4: Judge Review

After writing the puzzle, dispatch the `puzzle-judge` subagent:

```
Dispatch: puzzle-judge
Input: the full .pzl file content (including # narrative comments)
```

### Handling verdicts

- **PASS (12-15):** Puzzle is ready. Proceed to Step 5.
- **NEEDS_REVISION (8-11):** Read the judge's issues. Revise the puzzle addressing each one. Re-submit to judge. Max 3 rounds.
- **REJECT round 1 (1-7):** Go back to Step 1. Restart the reasoning chain with the judge's core objection as an additional constraint. Fresh attempt, not a patch.
- **REJECT round 2:** Escalate to the user. Show both attempts and the judge's reasoning. Ask which constraint to relax.
- **Round 3 without PASS:** Escalate to the user with full history.

## Step 5: Findings Log

After judge evaluation (any verdict), append an entry to `docs/puzzle-findings.md`:

```markdown
## YYYY-MM-DD — <puzzle-filename>.pzl

Score: N/15 (Focus:N Determinism:N Signal:N Minimality:N Documentation:N)
Verdict: <verdict> (round N)

Round 1 issues:
- <issue from judge>

Resolution: <what was changed, if revised>

Final card count: N (N non-land)
Mechanic tested: <one-line summary>
```

If the findings log doesn't exist yet, create it with this header:

```markdown
# Puzzle Findings Log

Quality telemetry from the puzzle writer-judge loop. Each entry records the judge's scoring, issues found, and resolutions applied.

## Recurring Patterns

(Updated periodically — common issues across puzzles)

---
```

When the log exceeds 30 entries, move older entries to `docs/puzzle-findings-archive/YYYY.md` and update the Recurring Patterns section.

## Step 6: Validation

After the judge passes the puzzle, run card validation:

```bash
just puzzle-check <puzzle-file-path>
```

`FAIL` = card has no grpId in Arena DB → will NPE at runtime. Fix by picking a different card (`just card-grp "<name>"` to search).

## Output

Final output to the user:
1. **The `.pzl` file** with narrative comments — ready to save
2. **Judge verdict + score** — the final round's evaluation
3. **Suggested test assertion** — what to check in MatchHarness beyond WIN/LOSE
4. **Suggested file path** — `matchdoor/src/test/resources/puzzles/<name>.pzl` (test fixture) or `puzzles/<name>.pzl` (acceptance target)
```

- [ ] **Step 2: Verify skill file is well-formed**

Run: `wc -l .claude/skills/write-puzzle/SKILL.md`
Expected: ~220-240 lines

- [ ] **Step 3: Commit**

```bash
git add .claude/skills/write-puzzle/SKILL.md
git commit -m "feat: upgrade write-puzzle skill with reasoning chain and judge loop"
```

---

### Task 3: Create findings log

**Files:**
- Create: `docs/puzzle-findings.md`

- [ ] **Step 1: Create the findings log with header**

```markdown
# Puzzle Findings Log

Quality telemetry from the puzzle writer-judge loop. Each entry records the judge's scoring, issues found, and resolutions applied.

## Recurring Patterns

(Updated periodically — common issues across puzzles)

---
```

- [ ] **Step 2: Commit**

```bash
git add docs/puzzle-findings.md
git commit -m "docs: create puzzle findings log for quality telemetry"
```

---

### Task 4: Calibrate judge against existing puzzles

Run the puzzle-judge subagent against 5 existing puzzles to validate the rubric produces sensible scores. Compare actual scores against expected ranges from the spec.

**Files:**
- Modify: `docs/puzzle-findings.md` (append calibration entries)
- Possibly modify: `.claude/agents/puzzle-judge.md` (rubric tuning if scores are off)

- [ ] **Step 1: Judge `legend-rule.pzl`**

Dispatch puzzle-judge with the content of `puzzles/legend-rule.pzl`.
Expected score range: 13-15 (tight puzzle, one win path, every card has a job, but no narrative comments).
Note: Documentation will score low (1) since there's no `#` narrative — that's expected for pre-existing puzzles.

- [ ] **Step 2: Judge `bolt-face.pzl`**

Dispatch puzzle-judge with `matchdoor/src/test/resources/puzzles/bolt-face.pzl`.
Expected: 10-13. Minimal, clear, but has Runeclaw Bear and Pillarfield Ox on AI battlefield (no stated purpose — are they alternative targets? dead weight?).

- [ ] **Step 3: Judge `fdn-keyword-combat.pzl`**

Dispatch puzzle-judge with `matchdoor/src/test/resources/puzzles/fdn-keyword-combat.pzl`.
Expected: 7-9. Multi-purpose stress test — many cards, multiple win paths, nondeterministic AI. Should score well on Documentation (has a detailed Description) but poorly on Focus and Determinism.

- [ ] **Step 4: Judge `lands-only.pzl`**

Dispatch puzzle-judge with `matchdoor/src/test/resources/puzzles/lands-only.pzl`.
Expected: 5-7. Degenerate — no mechanic, unwinnable (Goal:Win but no damage sources), but minimal by default.

- [ ] **Step 5: Judge `prowess-buff.pzl`**

Dispatch puzzle-judge with `matchdoor/src/test/resources/puzzles/prowess-buff.pzl`.
Expected: 6-8. Missing Description, malformed Goal field, nondeterministic AI (Grizzly Bears may or may not block).

- [ ] **Step 6: Compare scores against expected ranges**

Record all 5 scores. If any score is >3 points outside expected range, analyze why:
- Is the rubric wording ambiguous? → fix the agent definition
- Is the expected range wrong? → update the spec
- Is the judge misreading the puzzle? → add clarifying examples to the agent

- [ ] **Step 7: Apply any rubric fixes from calibration**

If fixes needed, edit `.claude/agents/puzzle-judge.md` with refined wording.

- [ ] **Step 8: Append calibration entries to findings log**

Add all 5 calibration entries to `docs/puzzle-findings.md` with a `## Calibration` header.

- [ ] **Step 9: Commit**

```bash
git add docs/puzzle-findings.md .claude/agents/puzzle-judge.md
git commit -m "feat: calibrate puzzle judge against 5 existing puzzles"
```

---

### Task 5: End-to-end validation

Write one new puzzle using the upgraded skill to verify the full writer-judge loop works.

- [ ] **Step 1: Invoke write-puzzle skill**

Ask: "Write a puzzle that tests deathtouch — a creature with deathtouch should kill any creature it deals damage to in combat, regardless of toughness."

The skill should:
1. Run the 7-step reasoning chain
2. Produce a `.pzl` with narrative comments
3. Dispatch the judge
4. Revise if needed
5. Append to findings log
6. Run `just puzzle-check`

- [ ] **Step 2: Verify the outputs**

Check:
- `.pzl` file has `#` narrative with all 6 sections
- Judge score is 12+ (PASS)
- `docs/puzzle-findings.md` has a new entry
- `just puzzle-check` passes

- [ ] **Step 3: Save the puzzle and commit**

```bash
git add matchdoor/src/test/resources/puzzles/deathtouch-kill.pzl docs/puzzle-findings.md
git commit -m "feat: add deathtouch puzzle — first puzzle from writer-judge loop"
```
