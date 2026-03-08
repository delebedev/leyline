## ObjectsSelected — field note

**Status:** NOT IMPLEMENTED
**Instances:** 5 across 3 sessions
**Proto type:** AnnotationType.ObjectsSelected = 31
**Field:** persistentAnnotations

### What it means in gameplay

Marks that a specific player has locked in their selection during a prompted or simultaneous-choice effect. The client uses it to show a "selection confirmed" or "waiting for opponent" visual state. The annotation persists until the choice is resolved, then is deleted.

### Detail keys

| Key | Always/Sometimes | Values seen | Meaning |
|-----|-----------------|-------------|---------|
| playerId | Always | 1, 2 | The seat number of the player who made the selection |

**Note:** Old field notes said "no detail keys." The variance report now shows `playerId` as always-present across all 5 instances. The old observation was incorrect — `playerId` is the key differentiator.

### Objects observed in affectedIds

| affectedIds value | Meaning |
|---|---|
| 0 | No specific target object (e.g. discard: the "chosen" card is implicit, selection is for the effect) |
| 289 (grp:75557 = Swamp) | A specific permanent targeted by the selection (e.g. Legend Rule choice, target selection) |

### Cards / scenarios observed

| Scenario | Session | gsId | affectedIds | playerId |
|----------|---------|------|-------------|---------|
| Liliana +1 ("each player discards") — player 1 locked in discard | 2026-03-01_11-33-28 | 189 | [0] | 1 |
| Discard phase — player 1 discards (Ouroboroid) | 2026-03-06_22-37-41 | 135 | [0] | 1 |
| Legend Rule choice — player 2 chose which to keep (Swamp targeted?) | 2026-03-06_22-37-41 | 161 | [289 → grp:75557] | 2 |
| Unknown selection | 2026-03-07_11-49-05 | 28 | [0] | 2 |

### Lifecycle

**Persistent annotation.** Appears in `persistentAnnotations` when the player confirms their selection. In the same GSM, the consequence ZoneTransfer (discard/sacrifice) also fires. The annotation is present in that final GSM and then absent from subsequent ones — effectively deleted at resolution.

In the gsId=161 case (Legend Rule), `ObjectsSelected` is a persistent annotation in the same GSM as `LayeredEffectDestroyed` and `ZoneTransfer` (SBA_LegendRule), suggesting it marks which legend the player chose to keep before the other was sent to the graveyard.

### Related annotations

- `ChoiceResult` — the sibling transient annotation that carries the actual chosen value. ObjectsSelected = "I've chosen"; ChoiceResult = "here's what I chose."
- `ZoneTransfer` — the outcome action in the same GSM.

### Our code status

- Builder: missing — no `ObjectsSelected` method in AnnotationBuilder.kt
- GameEvent: missing
- Emitted in pipeline: no

### Wiring assessment

**Difficulty:** Easy-Medium

The annotation is simple (just `playerId`), but the hook points vary by scenario:

- **Discard:** intercept after the player's discard choice is confirmed, before ZoneTransfer
- **Legend Rule:** intercept at SBA evaluation when two legends of the same name exist and the controller chooses one to keep
- **Simultaneous effects (Liliana +1):** the harder case — Forge resolves these sequentially, not truly simultaneously, so "player has selected" is implicit in the resolution order

Purely cosmetic — the game works correctly without it.

### Open questions

- Why does the Legend Rule case have `affectedIds=[289 → Swamp]` rather than the legend token (grp:98008)? Is it targeting the *kept* permanent, not the *sacrificed* one?
- In session 2026-03-07_11-49-05 gsId=28, what is the effect context for `playerId=2`?
