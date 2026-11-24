---
summary: "CoinFlip annotation: transient result of one coin flip during a resolution; CoinFlipResult codes, one row per flip, ordering inside the resolution and the PromptReq notification that follows."
entity: annotation/CoinFlip
---
# CoinFlip

## Meaning

Some effects flip a coin and act on whether the player won (CR 705).
`CoinFlip` publishes each flip's outcome in the GSM where the effect
resolves. A `PromptReq` then announces the outcome to the players; it asks
for nothing, and no call of heads or tails exists.

## Variants

Transient only, never combined with another type. One row per flip: an
effect that flips several coins carries several rows, in flip order.

## Fields

| Field | Role |
|---|---|
| `affectorId` | The resolving ability instance that flipped the coin. |
| `affectedIds` | The seat of the player who flipped, as a one-element list. |
| `CoinFlipResult` | The outcome as an Int32: `1` won, `2` lost. |

## Identity model

`affectorId` is an ability instance; `affectedIds` is a seat ID.
`CoinFlipResult` on the annotation is an outcome code. The prompt parameter
of the same name is a localization reference in another domain: `47` for won
and `48` for lost.

## Lifecycle

1. [ResolutionStart](ResolutionStart.md) for the ability.
2. One `CoinFlip` row per flip.
3. The annotations of the outcome, such as a `ModifiedLife`.
4. [ResolutionComplete](ResolutionComplete.md) and
   [AbilityInstanceDeleted](AbilityInstanceDeleted.md) for the ability.
5. After that GSM, one `PromptReq` per flip with `promptId` `46` and the
   GSM's `gameStateId`. Its `PlayerId` parameter is a `PlayerSeatId`
   reference to the flipper, and its `CoinFlipResult` parameter a
   `LocalizationId` reference to the outcome label.

The row lasts for its GSM only.

## Sibling shapes

[ChoiceResult](ChoiceResult.md) records a value a player chose; a coin flip
is random and has no choice. The flip's consequences are their own
annotations, not details of `CoinFlip`.

## Contract

- <a id="CoinFlip.transient"></a>**CoinFlip.transient.**
  `CoinFlip` `MUST` be carried in `annotations`, with exactly one seat in
  `affectedIds`.
- <a id="CoinFlip.result-code"></a>**CoinFlip.result-code.**
  `CoinFlipResult` `MUST` be `1` for a won flip and `2` for a lost flip.
- <a id="CoinFlip.within-resolution"></a>**CoinFlip.within-resolution.**
  The row `MUST` follow the `ResolutionStart` and precede the
  `ResolutionComplete` of the ability named by its `affectorId`, in the same
  GSM.
- <a id="CoinFlip.prompt"></a>**CoinFlip.prompt.**
  Each flip `MUST` be announced by a `PromptReq` with `promptId` `46` whose
  `CoinFlipResult` parameter references localization `47` for a won flip and
  `48` for a lost flip.

## Open points

- Does a flip made outside a resolution, or by an opponent's effect, use the
  same shape?
