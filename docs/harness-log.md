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
1. Fix drag reliability (hand position calculation)
2. Add prompt detection to `arena turn` (discard, explore, target selection)
3. Add `arena discard` command
4. Update gameplay skill with explore/discard recovery
5. Add controller to scry battlefield objects
6. Re-run 3 sessions to measure improvement
