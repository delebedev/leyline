---
summary: "ActivateMana action: activating a mana ability; offer fields, color choice within one ability, the accepted action and the stackless annotation bracket."
entity: action/Activate_Mana
---
# Activate_Mana

## Meaning and object model

`ActivateMana` activates a mana ability. It is separate from `Activate`
because mana abilities do not use the stack (CR 605.3b): the source's cost,
the produced mana and any payment it feeds are reported directly in the
state around the action. The activation gets an ability instance ID for its
annotations. Usually it has no [Ability](../object_types/Ability.md) object;
some activations project one in the Pending zone until the activation
completes (see [Activate_Mana.bracket](#Activate_Mana.bracket)).

`instanceId` names the source permanent; `abilityGrpId` names the mana
ability. One action row is one ability choice. A source with several mana
abilities has several rows; one ability that can make several colors has one
row with a color choice.

## Offer

| Field | Role |
|---|---|
| `actionType` | `ActivateMana`. |
| `instanceId` | The source permanent. |
| `abilityGrpId` | The mana ability's definition. |
| `grpId` | The source's definition, when present. |
| `facetId` | Usually the source's `instanceId`. |
| `uniqueAbilityId` | The matching row of the source's `uniqueAbilities`, when present. |
| `manaCost` | The activation cost of a mana ability that costs mana. |
| `isBatchable` | Whether the client may batch this activation with others. |
| `manaPaymentOptions` | One option per mana the ability can make. |
| `manaSelections` | The color menu for an ability that makes one of several colors. |

The minimal offer, for a basic land, is `instanceId` and `abilityGrpId`. A
multi-color ability adds a payment option per color and one selection:

```text
instanceId   = <source>
abilityGrpId = <mana ability>
manaPaymentOptions
  [0] mana = [{ color = Black, srcInstanceId = <source>, abilityGrpId = <ability> }]
  [1] mana = [{ color = Green, srcInstanceId = <source>, abilityGrpId = <ability> }]
manaSelections
  instanceId = <source>, abilityGrpId = <ability>, selectionCount = 1,
  validationType = NonRepeatable
  options
    [0] selectedColor = Black, mana = [{ color = Black, count = 1 }]
    [1] selectedColor = Green, mana = [{ color = Green, count = 1 }]
```

An ability that makes several mana at once, such as `{G}{G}` or a Signet's
`{W}{B}`, is not a color choice. Its one payment option lists every mana it
makes, with a count per color, and its selection has one option carrying the
same mana, with no `selectedColor` and no `selectionCount`:

```text
manaPaymentOptions
  [0] mana = [{ color = White, count = 1 }, { color = Black, count = 1 }]
manaSelections
  instanceId = <source>, abilityGrpId = <ability>
  options
    [0] mana = [{ color = White, count = 1 }, { color = Black, count = 1 }]
```

## Lifecycle

1. The client submits a `PerformActionResp` carrying an `ActivateMana` action
   of the offered row. There is no separate selected-color field: the chosen
   color is the one payment option left in `manaPaymentOptions`. The
   response may still carry the whole `manaSelections` menu.
2. The server reports an activation whose mana pays for something as one
   bracket, in this order:
   - `AbilityInstanceCreated`, with the source as `affectorId` and the
     activation's ability instance ID in `affectedIds`;
   - the cost's state changes, such as `TappedUntappedPermanent` for a tap;
   - `UserActionTaken` with `actionType` `4`;
   - `ManaPaid`, when a payment in progress consumes the mana at once;
   - `AbilityInstanceDeleted` for the activation's ability instance.

   When the activation's `Ability` object waits in Pending, the same rows are
   split across GSMs and `AbilityInstanceDeleted` arrives in a later one.
   The order of `UserActionTaken` and `AbilityInstanceDeleted` for an
   activation that only floats mana is an open point.
3. Mana that is not consumed at once appears in the player's `manaPool` as a
   `ManaInfo` with the source's instance ID, color, ability, count and mana
   ID.

## Sibling shapes

| Shape | Meaning |
|---|---|
| One row, one color | A basic land or another fixed-color source. |
| One row, several color options | One mana ability that makes one of several colors. |
| One row, one option with several mana | One mana ability that makes all of that mana at once. |
| Several rows on one source | The source has several mana abilities. |
| `FloatMana` | A request-rail affordance; the mana itself still comes from `ActivateMana`. |
| `Activate` | A non-mana activated ability, which uses the stack. |

"A dual land has two `ActivateMana` rows" is true only when the card has two
mana abilities; it is not how a multi-color ability is offered.

## Contract

- <a id="Activate_Mana.offer-identity"></a>**Activate_Mana.offer-identity.**
  Every `ActivateMana` offer `MUST` carry the source's `instanceId` and the
  mana ability's `abilityGrpId`.
- <a id="Activate_Mana.color-in-option"></a>**Activate_Mana.color-in-option.**
  When a submitted `ActivateMana` action's `manaPaymentOptions` name exactly
  one color, the server `MUST` read that color as the player's choice, even
  if `manaSelections` still lists several.
- <a id="Activate_Mana.bracket"></a>**Activate_Mana.bracket.** An accepted
  activation whose mana pays for a spell or cost `MUST` be reported, in
  order, by `AbilityInstanceCreated` from the source, the cost's state
  changes, `UserActionTaken` with `actionType` `4` naming the activation's
  ability instance, and `AbilityInstanceDeleted` for that instance. When the
  activation has an `Ability` object waiting in Pending, the rows are split
  across GSMs, and `UserActionTaken` still comes before
  `AbilityInstanceDeleted`.
- <a id="Activate_Mana.paid"></a>**Activate_Mana.paid.** When the mana pays
  for a spell being cast, a `ManaPaid` row with the source as `affectorId`
  and the spell as `affectedIds` `MUST` appear before the activation's
  `AbilityInstanceDeleted`.

## Open points

- When an activation's mana pays for nothing and only floats, does
  `AbilityInstanceDeleted` come before `UserActionTaken`? One such
  activation has shown that order.
- Is a Pending activation's ability instance always deleted, and in which
  GSM?
- Does every `FloatMana` decision end in one of the `ActivateMana` rows?
- When is `isBatchable` false?
- Is `manaSelections` sent for an ability that makes one fixed color?
