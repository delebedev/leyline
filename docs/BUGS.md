# Known Bugs

## Nexus

### BUG-001: Client stuck on game screen after concede

**Status:** Open
**Observed:** 2026-02-15
**Module:** forge-nexus

**Symptoms:** After pressing concede in client UI, the game screen remains visible. No exit menu, no post-game rewards screen. Client appears stuck — continues sending `ClientToGreuimessage` UI events.

**Server-side:** Concede processed correctly. Server sends the full game-end sequence:
```
[40] IN  ConcedeReq
[41] OUT GameStateMessage Diff (flush 1)
[42] OUT GameStateMessage Diff (flush 2)
[43] OUT GameStateMessage Diff (flush 3)
[44] OUT IntermissionReq
```

This matches the `arena-game-end` golden (3x empty Diff SendAndRecord + IntermissionReq).

**Likely cause:** Client expects additional messages after `IntermissionReq` to transition to post-game screen — possibly a `MatchCompleteNotification` or similar match-level message outside the GRE channel. The `IntermissionReq` alone may only signal "game over" but not "return to menu".

**Investigation pointers:**
- Check `reference/gre-protocol-reference.md` for post-game message flow
- Check full-game recordings for messages after IntermissionReq
- The match-level `MatchDoorConnectRequest` channel may need a separate completion message

---

### BUG-002: Cannot cast spells — missing ActivateMana actions

**Status:** Fixed (ActivateMana added; autoTapSolution still TODO)
**Observed:** 2026-02-15
**Module:** forge-nexus

**Symptoms:** After playing a land, client shows castable creatures in hand but clicking them does nothing. No `PerformActionResp` sent by client for Cast actions. Client sends `ClientToGreuimessage` UI events (clicks/hovers) but cannot initiate casting. Server eventually times out (120s) and auto-passes.

**Root cause:** `StateMapper.buildActions()` only builds `Cast`, `Play`, and `Pass` actions. Real Arena also sends:
- `ActivateMana` — for each untapped mana source (lets client tap lands)
- `autoTapSolution` (field 21 on Cast) — auto-tap hint for one-click casting

Without these, the client has no way to pay mana costs.

**Evidence:**
```
Our actions:   Cast, Cast, Cast, Pass
Arena golden:  ActivateMana, Cast, FloatMana, Pass
```

**Fix:** Add `ActivateMana` actions for untapped lands/mana-producers in `StateMapper.buildActions()`. Optionally add `autoTapSolution` on Cast actions.

**Why not caught by tests:** `StructuralDiff.compareShape()` skipped `actionTypes` entirely (marked "deck-dependent"). Golden-only tests documented Arena sends ActivateMana but no live-game test asserted our output includes it.

**Fix applied:** `StateMapper.buildActions()` now generates `ActivateMana` for untapped permanents with mana abilities. `compareShape()` now checks load-bearing action kinds (ActivateMana) at the sequence level. Still TODO: `autoTapSolution` on Cast actions for one-click casting.

---

### BUG-003: No pass/done button visible in client

**Status:** Open (may be same root cause as BUG-002)
**Observed:** 2026-02-15
**Module:** forge-nexus

**Symptoms:** No visible button to pass priority or advance the turn. Server sends `Pass` action in `ActionsAvailableReq` but client doesn't render it.

**Likely cause:** Related to BUG-002 — the client may suppress all action buttons when it detects an incomplete action set (missing ActivateMana). Alternatively, the prompt configuration or auto-pass settings may need additional fields.

**Investigation pointers:**
- Check if fixing BUG-002 (adding ActivateMana) also fixes the Pass button
- Check `reference/auto-pass-protocol.md` for prompt/button rendering requirements
