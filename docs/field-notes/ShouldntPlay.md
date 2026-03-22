## ShouldntPlay — field note

**Status:** NOT IMPLEMENTED
**Instances:** 15 across 1 session + 4 (Legendary) in 2026-03-21_22-05-00
**Proto type:** AnnotationType.ShouldntPlay
**Field:** annotations (transient — fires in the `annotations` array, never `persistentAnnotations`)

### What it means in gameplay

`ShouldntPlay` fires each time a card enters priority in the player's hand while it "shouldn't" be played this turn under normal circumstances — e.g. a land that enters tapped (so playing it this turn is suboptimal). The client uses it to display a subtle visual indicator (dim, tooltip, or badge) on the card in hand, flagging it as inadvisable to play right now.

This annotation is **advisory/cosmetic** — it has no enforcement effect. The player can still play the card; the server just informs the client that it's not ideal.

### Detail keys

| Key | Always/Sometimes | Values seen | Meaning |
|-----|-----------------|-------------|---------|
| Reason | Always | `EntersTapped`, `Legendary` | Why the card shouldn't be played. |

- `EntersTapped`: if you play this land now, it will enter tapped and not produce mana this turn.
- `Legendary`: you already control a legendary permanent with the same name — playing this copy would trigger the legend rule (sacrifice one).

### Cards observed

| Card name | grpId | instanceId | Role | Session |
|-----------|-------|------------|------|---------|
| Abandoned Air Temple (land, enters tapped) | 97544 | 229 | In opponent seat 2's hand; fires every priority point in main phases across many turns | 2026-03-06_22-37-41 |
| Abandoned Air Temple (second copy, reassigned to iid=310) | 97544 | 310 | instanceId reused after a previous card moved out; by gsId=155 iid=310 is Abandoned Air Temple in zone 35 (hand) | 2026-03-06_22-37-41 |
| Unknown land (private card) | unknown | 408 | Drawn at turn 10 draw step (from library), fires at main phase priorities on the same turn | 2026-03-06_22-37-41 |

**Note on instanceId reuse:** instanceId 310 started as Formidable Speaker (creature) on the stack, resolved to the battlefield, then the server reassigned instanceId 310 to a different card (Abandoned Air Temple) later in the game. By gsId=155, iid=310 is Abandoned Air Temple in zone 35. All `ShouldntPlay` instances for iid=310 are therefore the same card type (Abandoned Air Temple, enters tapped land).

**Note on instanceId 229 (Abandoned Air Temple):** this is a private card (opponent's hand, zone 35), so the annotation fires from the server's perspective tracking the opponent's options. The `ShouldntPlay` annotation appears for cards in both players' hands.

### Lifecycle

`ShouldntPlay` is a **transient annotation** — it fires once per GSM where the card is eligible to be played and the reason applies. It is NOT accumulated or persisted. In a typical turn:

- gsId=N (Main1 priority): `ShouldntPlay` fires for the tapped-land in hand
- gsId=N+1 (some other priority point): fires again if the card is still in hand and still inadvisable

Observed pattern for iid=229 across the session: fires at every main-phase priority point (gsIds: 6, 14, 42, 50, 94, 102, 141, 149, 198, 206 — 10 instances for this one card, spread across many turns). The card stayed in hand all game.

**Turn-scoped frequency:** fires multiple times per turn — once per priority opportunity while the reason holds. Not once-per-turn.

### Related annotations

No systematic co-occurrences identified. It fires standalone within the `annotations` array alongside the normal phase/priority annotations for that GSM.

### Our code status

- Builder: missing
- GameEvent: missing — no event for "this card shouldn't be played now"
- Emitted in pipeline: no

### Wiring assessment

**Difficulty:** Easy-Medium (EntersTapped) / Medium (Legendary)

#### EntersTapped

1. At each main-phase priority point, scan cards in hand
2. If a card is a land with an ETB tapped clause, emit `ShouldntPlay` with `Reason=EntersTapped`
3. Send to the **hand-owner's seat**
4. Fires at **every** main-phase priority point while the condition holds

Forge knows whether a land enters tapped via `KeywordAbility.ENTERS_TAPPED` or checking for a replacement effect. Hook: `GameStateMessage` builder, "player has priority in main phase" serialization.

#### Legendary

1. At each main-phase priority point (phase entry or stack-clear), scan the active player's hand
2. For each hand card that shares a name with a legendary permanent they control on battlefield, emit `ShouldntPlay` with `Reason=Legendary`
3. Send to the **opponent's seat** (not the hand-owner — opposite of EntersTapped)
4. Fires on **phase entry + stack-clear events**, not every priority point
5. `affectorId` = the battlefield legendary; `affectedIds` = [the hand card]

Full wiring analysis: `docs/plans/2026-03-21-shouldntplay-legendary-wire-spec.md`.

**Gaps:**
- Forge's "enters tapped" detection: `KeywordAbility.ENTERS_TAPPED` or checking for a replacement effect. Need to confirm the API.
- Other reasons: possible values `SorcerySpeed`, `ConditionNotMet`, `NotMainPhase`, `LandLimitReached`. Would need more recordings.
- Whether it fires for the non-active player's hand (opponent's perspective): yes, confirmed from iid=229 (opponent's card) for EntersTapped case.

This is purely cosmetic — missing it does not affect gameplay correctness, but it does affect the UX hint that tells players a land is a "bad" play.

### Legendary — Seat Routing Note

`EntersTapped` fires in the **hand-owner's stream**. `Legendary` fires in the **opponent's stream**. This is confirmed from `2026-03-21_22-05-00`: all 4 `ShouldntPlay(Legendary)` messages have `systemSeatIds=[2]` (sent to seat 2 = opponent of the player holding the second copy).

The `affectedId` for `Legendary` is a **private card** from the opponent's perspective — they receive the annotation but cannot see the grpId. The annotation appears to inform the client that the opponent holds a card they shouldn't play, though the practical UX effect on the opponent's side is unclear.

### Open questions

- Why does Formidable Speaker (a creature) trigger `ShouldntPlay` with `EntersTapped`? Does it have an ETB tapped ability not visible in the basic card data?
- Are there other `Reason` values? If `SorcerySpeed` exists (you're in opponent's turn, hand holds a sorcery), that would have higher UX value.
- Does it fire during the opponent's main phase for lands in the active player's hand? (i.e. "you can play this land on your turn, but it would enter tapped")
- For `Legendary`: does the annotation also fire in the hand-owner's own stream (not observed yet)? Possible that the server only sends it to one seat.
