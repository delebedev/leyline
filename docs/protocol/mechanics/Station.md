---
summary: "Station mechanic: the shared station activation, its tap-a-creature PayCostsReq, charge counters, and the StationLevelThreshold rows for each striation."
entity: mechanic/Station
---
# Station

Station is an activated ability of station cards: tap another untapped
creature you control and put charge counters equal to its power on the
station permanent, or to another characteristic when a static ability says
so. Each printed `{N+}` striation grants abilities, or makes the
permanent a creature, while the permanent has at least `N` charge counters.
The activation uses one ability shared by every station card, an
object-selection [PayCostsReq](../prompts/PayCostsReq.md), `CounterAdded` and
the persistent `Counter` row; each striation is tracked by an
[AbilityWordActive](../annotations/AbilityWordActive.md) row.

## Rules grounding

CR 702.184a defines the activation and its charge-counter result,
CR 702.184b the station symbols, and CR 702.184c static abilities that make
the count use a characteristic other than power. CR 721 defines how a station card's
striations change its characteristics.

## Surfaces

| Surface | Entity pages |
|---|---|
| Activation | [Ability](../object_types/Ability.md), [AbilityInstanceCreated](../annotations/AbilityInstanceCreated.md), [UserActionTaken](../annotations/UserActionTaken.md) |
| Tap cost | [PayCostsReq](../prompts/PayCostsReq.md) object-selection family, [TappedUntappedPermanent](../annotations/TappedUntappedPermanent.md) |
| Charge counters | `CounterAdded`, persistent `Counter` |
| Striation state | [AbilityWordActive](../annotations/AbilityWordActive.md) `StationLevelThreshold` |

## Activation lifecycle

1. The station permanent is offered as an `Activate` action with
   `abilityGrpId` `373`. Every station card uses this ability; striations have
   their own abilities.
2. On submission the server creates a stack `Ability` with `grpId` `373`,
   `parentId` the station permanent, and an `AbilityInstanceCreated` with
   `source_zone` the Battlefield.
3. The tap cost arrives as a `PayCostsReq` with `promptId` `14726`: an
   exact-count selection of one creature, each weight `1`, whose `CardId`
   parameter is the station ability
   ([PayCostsReq.select-exact](../prompts/PayCostsReq.md#PayCostsReq.select-exact)).
4. After the `EffectCostResp`, one GSM carries `TappedUntappedPermanent` for
   the chosen creature with the ability as `affectorId`, and `UserActionTaken`
   with `actionType` `2` and `abilityGrpId` `373`. No `ManaPaid` row is part of
   the activation.

## Resolution lifecycle

1. `ResolutionStart` with `grpid` `373`.
2. While the station permanent is still on the battlefield, `CounterAdded`
   names the ability as `affectorId` and the station permanent
   as affected, with `counter_type` `19` (charge) and `transaction_amount` the
   tapped creature's power when no static ability changes the characteristic
   used.
3. `ResolutionComplete` with `grpid` `373`, then `AbilityInstanceDeleted`.
4. The same GSM creates or updates the persistent `Counter` row on the
   permanent with `counter_type` `19` and the new total in `count`, and
   republishes each `StationLevelThreshold` row with the new `value`.

## Striation state

When a station card enters the Battlefield, its resolution GSM creates one
persistent `AbilityWordActive` row per printed striation, named
`StationLevelThreshold`, on the permanent itself:

| Detail | Meaning |
|---|---|
| `threshold` | The `N` of the striation's `{N+}` symbol. |
| `value` | The permanent's current charge counter count, `0` on entry. |
| `AbilityGrpId` | The striation's ability. |

The rows exist from entry, before any activation. `threshold` and
`AbilityGrpId` do not change; `value` follows the charge count. The same
resolution GSM also creates `LayeredEffectCreated` rows from the permanent
that later striation effects refer to.

## Sibling shapes

Crew, Saddle and Teamwork also tap creatures through an object-selection
`PayCostsReq`, but they use a total-power cost
([PayCostsReq.select-threshold](../prompts/PayCostsReq.md#PayCostsReq.select-threshold))
over any number of creatures. Station taps exactly one creature and turns its
power into counters at resolution.

## Contract

- <a id="Station.shared-ability"></a>**Station.shared-ability.** A station
  activation `MUST` be offered with `abilityGrpId` `373`, and its stack
  `Ability`, `ResolutionStart`, `ResolutionComplete` and `UserActionTaken`
  `MUST` carry `373`.
- <a id="Station.tap-prompt"></a>**Station.tap-prompt.** The tap cost `MUST`
  be requested as an exact-count object-selection `PayCostsReq` with
  `promptId` `14726`, `minSel` and `maxSel` `1`, and the station ability's
  instance ID as its `CardId` parameter.
- <a id="Station.cost-commit"></a>**Station.cost-commit.** The GSM that
  commits the tap cost `MUST` carry `TappedUntappedPermanent` for the tapped
  creature with the station ability as `affectorId`, and the activation's
  `UserActionTaken`.
- <a id="Station.charge-counters"></a>**Station.charge-counters.** When a station
  ability resolves while its permanent is still on the battlefield and the
  tapped creature's power is greater than 0, the resolution `MUST` carry `CounterAdded` with `counter_type` `19` naming
  the station permanent, and the same GSM `MUST` carry its persistent
  `Counter` row with the cumulative charge count.
- <a id="Station.threshold-rows"></a>**Station.threshold-rows.** While a
  station card is on the Battlefield, it `MUST` carry one persistent
  `AbilityWordActive` row named `StationLevelThreshold` per printed
  striation, with `affectorId` and affected ID the permanent, `threshold` the
  striation's level and `AbilityGrpId` the striation's ability.
- <a id="Station.threshold-on-entry"></a>**Station.threshold-on-entry.** The
  GSM in which a station card resolves onto the Battlefield `MUST` create its
  `StationLevelThreshold` rows, with `value` equal to its charge count.
- <a id="Station.threshold-value"></a>**Station.threshold-value.** A station
  resolution `MUST` republish each `StationLevelThreshold` row with `value`
  equal to the new charge count.

## Open points

- Which rows does a resolution carry when the charge count first crosses a
  striation, and when it crosses several at once?
- What does each entry-time `LayeredEffectCreated` stand for, given that their
  number differs from the number of striations?
- What happens to the rows when charge counters are removed?
- What does `transaction_amount` carry when a static ability makes station
  use a characteristic other than power?
- What does the resolution carry when the tapped creature's power is 0 or
  the station permanent has left the battlefield?
- How does a station card that enters without being cast publish its rows?
