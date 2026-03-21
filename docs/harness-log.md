# Gameplay Harness Log

Operator journal for the self-improving automation harness. Each entry captures: what was tested, what broke, what to fix next. Future agents read this before play sessions.

---

## 2026-03-21 — Initial harness + 3 bot match sessions

### Context
Goal: build a harness that makes agents better at driving Arena over time. Three layers:
1. **Gameplay skill** (`.claude/skills/gameplay/`) — MTG rules, turn structure, decision framework
2. **`arena turn` command** — structured scry state for agent decision-making
3. **Post-session analysis** — debrief via session journals + `arena issues`

### What was built
- Gameplay skill with hard rules (1 land/turn, cast from actions only, don't loop), play modes (mining/attack/defend), turn loop structure, recovery recipes
- `arena turn` CLI command — combines scry into agent-friendly output: turn/phase, hand, available actions (lands/casts/other), battlefield

### Test sessions
Three bot matches with sonnet, different decks/modes, target: turn 6.

**Session 1 — Black aggro, attack mode**
- Played to T8. Cast creatures, attacked, dealt damage. Life 15-14.
- Surveil modals (Festerleech/Gorehound ETB) blocked actions until manually dismissed
- `arena play` drag intermittently failed; internal retry sometimes recovered
- Agent followed 1-land rule correctly

**Session 2 — Green stompy, mining mode**
- Near-total drag failure. Most `arena land` and `arena play` calls failed.
- Explore modal (Cenote Scout) blocked all actions — agent eventually dismissed at 480,491
- Mining mode combat logic worked (attacked when ahead on creatures)
- Only ~2 cards successfully played in 6 turns

**Session 3 — Black control, defend mode**
- Drag failures from T1. Barely played anything.
- Reached T6 with 8 cards → discard prompt (hand > 7 at EOT)
- Agent had no vocabulary for discard (click card + Submit at 886,504)
- `arena turn` showed misleading "Can play land:" during discard prompt
- Defend mode correct (never attacked)

### Findings

**What worked:**
- `arena turn` — agents used it correctly as decision context
- Skill rules — no double-land violations, mode directives followed
- Journaling — useful turn-by-turn logs produced
- `arena attack-all` — reliable when in correct phase

**What broke (priority order):**

| # | Issue | Impact | Root cause |
|---|-------|--------|------------|
| P0 | Drag failures | Cards unplayable ~50% of time | Hand position detection drifts as hand size changes; OCR/scry coord mapping fragile |
| P1 | Discard prompt unhandled | Agent stuck at EOT with 8+ cards | No `arena turn` detection, no `arena discard` command, no skill guidance |
| P1 | Explore modal blocks | All actions blocked until dismissed | Not auto-dismissed like Surveil; needs explicit 480,491 click |
| P2 | No controller on battlefield | Can't distinguish our vs opp creatures | Scry zone model lacks per-object controller |
| P2 | `arena turn` during prompts | Shows misleading "Can play land:" | No prompt-type detection in turn output |
| P3 | Session log gaps | Subagent bash errors bypass session_log | Only CLI-wrapped commands are logged |

### Next steps
1. ~~Fix drag reliability (hand position calculation)~~ → DONE (bounds TTL)
2. ~~Add prompt detection to `arena turn`~~ → DONE (OCR prompt scan)
3. ~~Update gameplay skill with explore/discard recovery~~ → DONE (prompt section)
4. Add controller to scry battlefield objects
5. Test prompt handling in a game that actually triggers discard/explore
6. Session log coverage for subagent bash errors

---

## 2026-03-21 — Hardening fixes + verification

### Root cause analysis
Investigated why `arena play`/`arena land` drag was failing ~50%. Three problems scoped:

1. **Window bounds cache (5s TTL)** — the actual root cause of drag failures. Between
   `arena land` and `arena play`, stale cached bounds meant all coordinates were wrong.
   OCR captured wrong region → detection failed → estimation used → drag missed.
2. **Unhandled modals** — Explore and Discard not in `_MODAL_PATTERNS` regex.
   Discard uses Submit button (886,504) not Done (480,491).
3. **`arena turn` blind to prompts** — scry only reports ActionType_* actions,
   not UI prompts. Agents saw misleading "Can play land:" during discard.

Key insight: playing against real server (not forge), so all prompts are client UI —
no engine-side fix needed. Pure arena tooling fixes.

### Fixes applied
- **Bounds TTL 5s→1s** (`macos.py`) — 1 line, fixes coordinate staleness
- **Modal patterns** (`gameplay.py`) — added Explore, Discard; Submit button for discard
- **Prompt detection** (`turn.py`) — OCR scan prints `PROMPT:` line in `arena turn`
- **Gameplay skill** — new Prompts section with handling instructions

### Verification: post-fix bot match (sonnet, attack mode)
- **Zero drag failures** — 4 lands + 2 creatures all played successfully
- **Zero errors** — clean session start to T7
- Attacked once, dealt 2 damage (opp 20→18)
- No prompts triggered (red deck, no surveil/explore/discard)

Before fix: ~50% drag failure. After: 0%. Bounds cache was the whole problem.

### Remaining gaps
- Prompt handling (discard/explore) not yet tested in live game
- Battlefield controller split still missing from scry
- Subagent session log coverage gap

---

## 2026-03-21 — Forge local server testing (2 games)

### Context
Same fixes, tested against `just serve` (local forge engine) instead of proxy to real server.

### Results

**Game 1 — Green stompy, attack mode**
- Played to T6. Life 17-20. Barkhide Troll + Pelt Collector on board.
- Attacked T4 (3 dmg) and T6.
- `arena land` returned error but land appeared on battlefield — false negative.

**Game 2 — Red/Goblin, attack mode**
- Played to T6. Life 18-20. 5 creatures on board.
- Same land drag false-negative pattern.
- Forge auto-advanced turns and auto-cast some cards.

### Forge-specific findings

| Issue | Cause | Impact |
|-------|-------|--------|
| `arena land` reports failure but land resolves | Forge processes play faster than scry verification polls | False error, agent thinks play failed |
| Forge auto-advances phases | Engine skips empty priority windows | Turn numbers jump (T1→T4), agent misses windows |
| Forge auto-plays cards | AI engine acts on human seat in some cases | Agent's `arena play` races with engine |

**Root cause:** Forge resolves actions synchronously and fast. The verified drag polls
scry at 300ms intervals looking for zone change, but forge has already processed the
action and moved to the next phase. Scry sees the NEW state (card on battlefield) but
the zone-change check may compare against a stale pre-state that was already overwritten.

### Implications
- Real server: bounds fix solved drag failures completely (0% error)
- Forge server: drag verification needs longer/faster polling or forge-aware timing
- These are separate code paths — real server fix doesn't break forge, forge needs its own tuning
