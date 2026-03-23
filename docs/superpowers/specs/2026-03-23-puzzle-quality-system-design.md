# Puzzle Quality System

Writer-judge pattern for producing high-quality `.pzl` puzzles. The writer agent turns a mechanic description into a puzzle with a design narrative. The judge agent scores it and gives structured feedback. They iterate up to 3 rounds before escalating to a human.

## Problem

Current puzzles vary widely in quality. `legend-rule.pzl` is tight — 7 cards, one win path, every card has a job. `fdn-keyword-combat.pzl` has 13 cards, multiple win paths, nondeterministic AI. The `write-puzzle` skill has card format rules but no guidance on *thinking through* how a board state forces the mechanic under test.

The gap isn't syntax — it's design reasoning. A good puzzle is a proof: "this board state can only be solved through mechanic X, and each failure mode tells you something different."

## Components

### 1. Puzzle Writer (upgraded skill)

Enhanced `.claude/skills/write-puzzle/` skill. The core change: structured reasoning process before emitting the `.pzl` file.

**Writer reasoning chain:**

1. **Identify the mechanic** — what exactly is being tested? Not "combat" but "first strike damage assignment in the first combat damage step."
2. **Find the forcing function** — what board state makes this mechanic the *only* path to victory? Close off alternatives: big blockers prevent combat wins when testing spells, no burn prevents spell wins when testing combat.
3. **Choose deterministic AI behavior** — forced attackers (Juggernaut), life totals that compel defense, no AI hand cards unless survival-critical. Reference `puzzle-design.md` AI behavior traps.
4. **Map failure modes** — for each outcome (WIN/LOSE/TIMEOUT/CRASH), what does it tell you? If two failure modes are indistinguishable, the puzzle needs redesign.
5. **Minimize cards** — every card must have a stated job. If you can remove a card and the puzzle still tests the mechanic, remove it.
6. **Set exact life totals** — life should be a forcing function, not flavor. AILife=3 with Lightning Bolt = exact lethal. HumanLife=5 with a 5-power attacker = must block or die.
7. **Trace protocol path** — check `docs/catalog.yaml` for mechanic status. If the path touches something marked `missing`, note it — the puzzle becomes an acceptance test for that gap.

**Writer output:**

1. **Design narrative** — embedded as `#` comments at the top of the `.pzl` file. Structured sections:
   - `# Mechanic:` — what's being tested
   - `# Forcing:` — why this board forces the mechanic
   - `# AI behavior:` — why AI acts predictably
   - `# Failure modes:` — WIN/LOSE/TIMEOUT meaning
   - `# Card roles:` — each card's job (one line per card)
   - `# Protocol path:` — what message types this exercises
2. **The `.pzl` file** — with narrative comments
3. **Suggested test assertion** — what to check in MatchHarness beyond WIN/LOSE

### 2. Puzzle Judge (subagent)

A Claude subagent that evaluates puzzle quality. Not a script — it reads the puzzle, the narrative, and the design guide, then produces a structured verdict.

**Subagent type:** `puzzle-judge` (new entry in `.claude/agents/`)

**Judge input:** the `.pzl` file content (including `#` comment narrative)

**Evaluation dimensions (1-3 each):**

| Dimension | 1 (weak) | 2 (adequate) | 3 (strong) |
|-----------|----------|--------------|------------|
| **Focus** | Multiple win paths, unclear what's tested | One main path but alternatives exist | Single win path, mechanic is the only way |
| **Determinism** | AI has unforced choices with multiple valid plays (e.g., block assignment options, optional casts) | Life totals guide AI toward one line but edge cases exist (e.g., AI *should* attack but might not if it evaluates trades individually) | AI has zero decision points OR all decisions are forced by rules text (must attack) / exact lethal math |
| **Signal** | WIN/LOSE ambiguous | Most failure modes distinguishable | Every outcome maps to a specific diagnosis |
| **Minimality** | >8 non-land cards or obvious dead weight | 5-8 non-land cards, mostly justified | <5 non-land cards, every card has a stated job, nothing removable |
| **Documentation** | No narrative or vague description | Narrative present but missing sections | All 6 narrative sections present: Mechanic, Forcing, AI behavior, Failure modes, Card roles, Protocol path |

**Paired puzzles:** When a puzzle is part of a complementary pair (per `puzzle-design.md` Paired Puzzles pattern), add `# Paired with: <filename>` to the narrative. The judge evaluates Focus and Signal across the pair, not individually — each puzzle in the pair may have a looser focus because the pair provides disambiguation.

**Score:** sum of dimensions, out of 15.

**Verdict:**
- **PASS** (12-15) — puzzle is ready
- **NEEDS_REVISION** (8-11) — specific issues listed, writer revises
- **REJECT** (1-7) — fundamentally flawed design, needs rethinking

**Judge output format:**

```
Score: 13/15 (Focus:3 Determinism:3 Signal:3 Minimality:2 Documentation:2)
Verdict: PASS

Strengths:
- Single win path through prowess trigger
- Exact lethal with buff, no alternative

Issues:
- Minimality: Forest on battlefield serves no purpose (no green spells)
- Documentation: Protocol path section missing
```

### 3. Writer-Judge Loop

```
Writer produces puzzle + narrative
  → Judge evaluates
    → PASS: done, append to findings log
    → NEEDS_REVISION: writer receives issues, revises, re-submits (max 3 rounds)
    → REJECT (round 1): writer restarts reasoning chain from step 1
        with judge's core objection as an additional constraint.
        Fresh attempt, not a patch on the old one.
      → REJECT (round 2): escalate to human with both attempts + judge reasoning.
        Ask which constraint to relax.
    → Round 3 without PASS: escalate to human with full history
```

Each round's score and issues are preserved for the findings log.

### 4. Findings Log

`docs/puzzle-findings.md` — append-only log of judge evaluations. One entry per puzzle per session.

**Format:**

```markdown
## 2026-03-23 — prowess-lethal.pzl

Score: 13/15 (Focus:3 Determinism:3 Signal:3 Minimality:2 Documentation:2)
Verdict: PASS (round 2)

Round 1 issues:
- Forest on battlefield serves no purpose (no green spells)
- Protocol path section missing

Resolution: removed Forest, added protocol path to narrative

Final card count: 6 (3 non-land)
Mechanic tested: prowess trigger on noncreature cast
```

Over time this log reveals patterns: recurring quality issues feed back into writer skill improvements.

**Rotation:** When the log exceeds 30 entries, the writer moves older entries to `docs/puzzle-findings-archive/YYYY.md` and updates a "Recurring Patterns" section at the top of the findings log summarizing common issues seen across all puzzles.

## Puzzle File Format (with narrative)

```ini
# Design Narrative
# Mechanic: Prowess — +1/+1 until EOT when noncreature spell cast
# Forcing: Swiftspear (1/2) can't attack through Courser (3/3) without buff.
#   Giant Growth (+3/+3) + prowess (+1/+1) = 5/6, kills Courser and survives.
#   AI at 4 life = exact lethal only with prowess firing.
# AI behavior: AI has one creature (Courser), will block the only attacker.
#   No AI hand cards. No AI decision points.
# Failure modes:
#   WIN   = prowess triggered, buff applied, Swiftspear killed Courser, dealt 4
#   LOSE  = prowess didn't fire, Swiftspear died to Courser or didn't deal lethal
#   TIMEOUT = engine hung during spell resolution
# Card roles:
#   Monastery Swiftspear — prowess creature, the test subject
#   Giant Growth — noncreature spell to trigger prowess, +3/+3 buff
#   Forest — mana source for Giant Growth (1G)
#   Centaur Courser — blocker that requires prowess buff to overcome
# Protocol path: SpellAbility → resolve → LayeredEffectCreated annotation

[metadata]
Name:Prowess Lethal
Goal:Win
Turns:2
Difficulty:Easy
Description:Cast Giant Growth on Swiftspear to trigger prowess. Combined +4/+4 buff lets it attack through Courser for exact lethal.

[state]
ActivePlayer=Human
ActivePhase=Main1
HumanLife=20
AILife=4
removesummoningsickness=true

humanbattlefield=Monastery Swiftspear;Forest
humanhand=Giant Growth
humanlibrary=Forest;Forest;Forest;Forest;Forest
aibattlefield=Centaur Courser
ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
```

## Implementation Plan

### Step 1: Puzzle Judge subagent

Create `.claude/agents/puzzle-judge.md` with:
- Scoring rubric (the 5 dimensions above)
- Required reading in preamble: `docs/puzzle-design.md` (design principles, AI behavior traps, templates), `docs/puzzles.md` (architecture, goal types, format reference)
- Context-aware: evaluate fitness for the puzzle's stated purpose (Description field)
- Output format: score, verdict, strengths, issues

### Step 2: Upgrade write-puzzle skill

Update `.claude/skills/write-puzzle/` with:
- The 7-step reasoning chain (identify mechanic → trace protocol)
- Narrative comment format specification
- Auto-dispatch judge after producing puzzle
- Revision loop (max 3 rounds)
- Append findings log entry on completion

### Step 3: Findings log

Create `docs/puzzle-findings.md` with header explaining the format. Entries appended by the writer skill after judge evaluation.

### Step 4: Validate against existing puzzles

Run the judge on 3-5 existing puzzles to calibrate scoring:
- `legend-rule.pzl` (should score 13-15)
- `bolt-face.pzl` (should score 12-14)
- `fdn-keyword-combat.pzl` (should score 7-9, multi-purpose stress test)
- `lands-only.pzl` (should score 5-7 — degenerate: no mechanic, unwinnable, but minimal by default)
- `prowess-buff.pzl` (should score 6-8, missing fields + nondeterministic)

Calibration results feed into rubric tuning before finalizing.

## What This Doesn't Cover

- **Puzzle linter** — deferred. The judge + strong writer instructions handle content quality. Syntax linting (card existence, modifier validation) stays in `just puzzle-check` for now.
- **Automated puzzle generation from catalog gaps** — future work. Could scan `docs/catalog.yaml` for `status: missing` mechanics and auto-generate puzzle skeletons.
- **MatchHarness integration** — the writer suggests test assertions but doesn't write the Kotlin test. That's a separate step in puzzle-driven-dev.
