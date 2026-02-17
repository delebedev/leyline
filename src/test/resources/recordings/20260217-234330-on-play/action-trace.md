# Action Trace: on-play recording

**Recording:** `20260217-234330-on-play`
**Perspective:** Seat 2 (player) on the play
**Messages:** 31 GRE messages from 16 .bin files
**gsId range:** 3–46

## Game Summary

Player (seat 2) is on the play. Opponent Sparky (seat 1) concedes at turn 3.
Only the first land drop is visible in detail; the rest of the game is observed
from seat 2's limited perspective (opponent actions are HiFi-only to seat 1).

## Turn 0 — Pre-Game

| # | gsId | greType | Key Event |
|---|------|---------|-----------|
| 0 | — | UIMessage | Initial UI setup |
| 1 | 3 | GSM Diff | Seat 2 sees opening hand: 7 cards (instanceIds 219–225). Available actions: Cast 4 creatures + Play 3 lands. Phase=None, turn=0 |
| 2 | 3 | PromptReq | Prompt 37 sent to both seats |
| 3 | 3 | MulliganReq | Mulligan decision for seat 2 (prompt 34) |

## Turn 1 — Seat 1 Active (Sparky)

| # | gsId | greType | Key Event |
|---|------|---------|-----------|
| 4 | 3 | SetSettingsResp | Sparky settings ack |
| 5 | 4 | GSM Diff→seat1 | Beginning/Upkeep. Annotations: PhaseOrStepModified (Upkeep). Both players at 20 life |
| 6 | 5 | GSM Diff→seat1 | Priority passes to seat 2 |
| 7 | 6 | GSM Diff→seat1 | **Main1** — seat 1 has priority. 7 actions available |
| 8 | 6 | PromptReq | Prompt 37 to both seats |
| 9 | 6 | ActionsAvailable→seat1 | **Seat 1 can:** Cast 5 creatures (grpIds: 70298, 79618, 90499, 78995, 70679), Play 2 lands (grpId 98595=Forest), or Pass |

### Play Land (seat 1)

| # | gsId | greType | Key Event |
|---|------|---------|-----------|
| 11 | 7 | GSM Diff→seat1 | **Play Land:** instanceId 161 → **ObjectIdChanged** to 279. ZoneTransfer Hand(31)→Battlefield(28), category=PlayLand. Forest (grpId 98595) enters battlefield. Limbo gets old id 161. New persistent: EnteredZoneThisTurn, ColorProduction(green=5) |
| 12 | 7 | ActionsAvailable→seat1 | Now has ActivateMana on 279, plus casts and 1 remaining Play |
| 13 | 7 | GSM Diff→seat2 | **Same gsId=7, different .bin file.** Seat 2 sees the same zone/battlefield changes but does NOT see instanceId 161 in Limbo (no Private viewer). seat 2's hand remains unchanged |

## Turns 1–3 (seat 2 perspective, limited)

Messages 14–22 are all `UIMessage` to seat 2 — Sparky is playing out turns.
No GSM data is sent to seat 2 for these actions.

| # | gsId | greType | Note |
|---|------|---------|------|
| 14–22 | — | UIMessage (×9) | Sparky playing turns, seat 2 just gets UI pings |
| 23 | 26 | SetSettingsResp | Settings sync |
| 24 | — | UIMessage | More UI |
| 25 | 43 | SetSettingsResp | Settings sync |
| 26 | 43 | SetSettingsResp | Settings sync (again) |

## Turn 3 — Game End

| # | gsId | greType | Key Event |
|---|------|---------|-----------|
| 27 | 44 | GSM Diff→seat2 | **Seat 1 PendingLoss** (life=20, status=PendingLoss). Sparky conceded. Seat 2 gains new cast/play actions including instanceId 282, ActivateMana on 283 |
| 28 | 45 | GSM Diff→seat2 | Follow-up diff, same actions |
| 29 | 46 | GSM Diff→seat2 | Main1 turn 3, activePlayer=1 |
| 30 | 46 | IntermissionReq | Game over — intermission prompt sent to seat 2 |

## Key Observations

1. **Dual delivery:** gsId=7 (Play Land) is delivered in two separate .bin files — one for seat 1 (with Private Limbo object visible), one for seat 2 (without Private object). Same msgId, same gsId.
2. **ObjectIdChanged:** When a card moves from Hand to Battlefield, the instanceId changes (161→279). The old id goes to Limbo. This is the canonical zone-transfer pattern.
3. **Sparse seat 2 perspective:** Most of the game (messages 14–22) is just UIMessages to seat 2. The opponent's detailed game state changes only come through the HiFi stream to seat 1.
4. **Actions list per-seat:** The actions available to seat 1 and seat 2 are different objects with different instanceIds — each seat sees their own cards.
5. **PendingLoss:** Concession shows as `status: PendingLoss` on the losing player, not as a separate message type.
