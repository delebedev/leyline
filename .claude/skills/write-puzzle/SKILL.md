---
name: write-puzzle
description: Write a Forge .pzl puzzle file for a specific game scenario. Structured reasoning, narrative, judge review, findings log.
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

## Card selection

**Prefer cards from recordings.** When writing a puzzle for a conformance issue, the GitHub issue or recording notes name the exact cards observed in the real server session. Use those — they're guaranteed to exist in both Forge and Arena. Check the issue body, `recordings/*/notes.md`, and `just wire cards` for card names.

**Verify before designing.** Run `just card-grp "<name>"` for every non-basic-land card BEFORE committing to a design. A missing card forces a redesign — better to catch it early than after writing the narrative.

**Simple cards for generic roles.** When no recording card applies, use these known-good cards:

| Role | Cards |
|------|-------|
| Must-attack | Juggernaut (5/3) |
| Deathtouch blocker | Typhoid Rats (1/1), Hired Poisoner (1/1) |
| Vanilla beater | Grizzly Bears (2/2), Centaur Courser (3/3), Savannah Lions (2/1) |
| Big blocker | Those Who Serve (2/4), Kalonian Tusker (3/3) |
| Burn spell | Lightning Bolt (3 dmg), Shock (2 dmg), Lava Axe (5 dmg) |
| Pump spell | Giant Growth (+3/+3) |
| Flying | Healer's Hawk (1/1 flying lifelink), Wind Drake (2/2 flying) |
| Prowess | Monastery Swiftspear (1/2 haste prowess) |

This palette covers 90% of puzzle needs. Avoid complex cards unless testing their specific mechanic.

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
- **Life-total forcing:** HumanLife=3 with AI having 3+ power creatures -> AI always attacks
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

Narratives should be clean conclusions, not draft reasoning. No "wait, actually..." — resolve uncertainties before writing.

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
Dispatch: puzzle-judge subagent
Input: the full .pzl file content (including # narrative comments)
```

### Handling verdicts

- **PASS (12-15):** Puzzle is ready. Proceed to Step 5.
- **NEEDS_REVISION (8-11):** Read the judge's issues. Revise the puzzle addressing each one. Re-submit to judge. Max 3 rounds.
- **REJECT round 1 (1-7):** Go back to Step 1. Restart the reasoning chain with the judge's core objection as an additional constraint. Fresh attempt, not a patch.
- **REJECT round 2:** Escalate to the user. Show both attempts and the judge's reasoning. Ask which constraint to relax.
- **Round 3 without PASS:** Escalate to the user with full history.

## Step 5: Findings Log

After judge evaluation (any verdict), append an entry to `docs/puzzle-findings.md`.

**Token tracking:** Each agent dispatch result includes `total_tokens` in its metadata. After each judge round, note the token count. Sum all rounds for the total. This is the caller's responsibility — the judge doesn't know its own cost.

```markdown
## YYYY-MM-DD — <puzzle-filename>.pzl

Score: N/15 (Focus:N Determinism:N Signal:N Minimality:N Documentation:N)
Verdict: <verdict> (round N)
Tokens: judge=Nk, rounds=N, total_agent=Nk

Round 1 issues:
- <issue from judge>

Resolution: <what was changed, if revised>

Final card count: N (N non-land)
Mechanic tested: <one-line summary>
```

If the findings log doesn't exist yet, create it with the header from `docs/puzzle-findings.md`.

When the log exceeds 30 entries, move older entries to `docs/puzzle-findings-archive/YYYY.md` and update the Recurring Patterns section.

## Step 6: Validation

After the judge passes the puzzle, run card validation:

```bash
just puzzle-check <puzzle-file-path>
```

`FAIL` = card has no grpId in Arena DB -> will NPE at runtime. Fix by picking a different card (`just card-grp "<name>"` to search).

## Output

Final output to the user:
1. **The `.pzl` file** with narrative comments — ready to save
2. **Judge verdict + score** — the final round's evaluation
3. **Suggested test assertion** — what to check in MatchHarness beyond WIN/LOSE
4. **Suggested file path** — `matchdoor/src/test/resources/puzzles/<name>.pzl` (test fixture) or `puzzles/<name>.pzl` (acceptance target)

## Step 7: Writer Reflection

After completing the puzzle, append a brief reflection to `docs/puzzle-writer-learnings.md`. This is self-feedback — what was hard, what took the most thinking, what you'd do differently.

```markdown
### YYYY-MM-DD — <puzzle-filename>.pzl

**Hardest part:** <what took the most thinking — card selection? forcing function? AI behavior?>
**Surprising:** <anything unexpected — card didn't exist, AI wouldn't cooperate, failure modes overlapped?>
**Would change:** <what you'd do differently next time, if anything>
```

This log accumulates friction points that feed back into improving the skill. If the same issue appears 3+ times, it should become a rule or a palette entry.
