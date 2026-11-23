---
summary: "ReplacementEffect annotation: links a replacement ability or lifecycle to the object whose event it modifies; rails, fields, identities and the entry-choice lifecycle."
read_when:
  - "emitting or consuming a ReplacementEffect annotation"
  - "publishing a choice that is made before a permanent enters"
  - "reviewing replacement-effect emission or its retirement"
entity: annotation/ReplacementEffect
---
# ReplacementEffect

## Meaning

A replacement effect modifies an event as it happens instead of reacting to
it afterward. `ReplacementEffect` links the replacement ability, or the
lifecycle of one pending replacement, to the object whose event is being
modified.

## Rules grounding

A replacement effect must exist before the event it replaces (CR 614.1,
CR 614.4). A choice required by a replacement effect that modifies how a
permanent enters the battlefield is made before the permanent enters
(CR 614.12a).

## Variants

- **Transient row** — describes one replacement event in the GSM where the
  event happens.
- **Persistent row** — represents replacement state that stays live until an
  event, a choice or a source transition retires it.

The annotation type does not select the rail; each emission does. The
pay-life entry choice of a land such as Temple Garden, whose controller either
pays 2 life or has it enter tapped, is the persistent branch this page
specifies in full.

## Fields

| Field | Role |
|---|---|
| `affectorId` | The source card, or an identity for one replacement lifecycle, depending on the branch. On the pay-life entry branch it is a lifecycle identity shared with the choice prompt and the payment rows. |
| `affectedIds` | The affected object. A row naming a player instead of an object also exists; see Open points. |
| `grpid` | The replacement ability's definition. |
| `ReplacementSourceZcid` | When present, the source card's instance ID before the zone change being replaced. |
| `replacedEffectRecipient`, `replacedEffectSource`, `replacementEffectSpawnerType` | Branch-specific context; see Open points. |

## Identity model

Three identities meet on one row and must not be conflated:

- the ability definition in `grpid`;
- the source or lifecycle identity in `affectorId`;
- the affected object in `affectedIds`.

`ReplacementSourceZcid` preserves the source card's identity from before its
zone change. On the entry branch the incoming object already has its new
instance ID in `affectedIds`; `ReplacementSourceZcid` is the old one, never the
row's subject.

## Lifecycle

Pay-life entry choice, for example playing Temple Garden from hand:

1. A GSM projects the incoming land under a newly allocated instance ID, public
   and not yet in a zone. In the same GSM a persistent `ReplacementEffect` row
   names that new ID in `affectedIds`, the card's hand instance ID in
   `ReplacementSourceZcid`, and the land's replacement ability in `grpid`.
2. An `OptionalActionMessage` follows in the same message batch. Its
   `sourceId` is the row's `affectorId`; its prompt's `CardId` parameter is
   the incoming instance ID.
3. The client answers.
4. The next GSM commits the entry: `ObjectIdChanged` maps the hand ID to the
   incoming ID, `ZoneTransfer` moves the land to the battlefield, and the
   persistent row is deleted. When the player pays, `SyntheticEvent` and
   `ModifiedLife` record the life payment with the row's `affectorId`.

Other branches follow the general pattern: a transient row lasts for its
event; a persistent row stays in game state while the replacement is pending
or applicable, and resolving the event or choice retires it.

## Sibling shapes

`ReplacementEffectApplied` is a separate transient annotation for damage
replacement. It carries `IsDamageReplacement` and accompanies the resulting
damage event. Because `ReplacementEffect` can also be transient, the two types
are not a state-versus-event pair.

## Contract

- <a id="ReplacementEffect.etb-incoming"></a>**ReplacementEffect.etb-incoming.**
  On the pay-life entry branch, the GSM that precedes the choice `MUST` project
  the incoming object under a newly allocated instance ID, `Public`, with
  `zoneId` `0`, and `MUST` carry a persistent `ReplacementEffect` row whose
  `affectedIds` is exactly that ID and whose `grpid` equals the incoming
  object's replacement ability grpId.
- <a id="ReplacementEffect.source-zcid"></a>**ReplacementEffect.source-zcid.**
  When present, `ReplacementSourceZcid` `MUST` be the source card's instance
  ID before the replaced zone change. On the pay-life entry branch the
  `ObjectIdChanged` that admits the object `MUST` map `orig_id` equal to
  `ReplacementSourceZcid` to `new_id` equal to the row's `affectedIds`.
- <a id="ReplacementEffect.etb-choice-source"></a>**ReplacementEffect.etb-choice-source.**
  On the pay-life entry branch, the `OptionalActionMessage` that follows the
  GSM `MUST` carry `sourceId` equal to the row's `affectorId`, and its prompt's
  `CardId` parameter `MUST` equal the row's `affectedIds`.
- <a id="ReplacementEffect.etb-payment-affector"></a>**ReplacementEffect.etb-payment-affector.**
  On the pay-life entry branch, when the player pays, the `SyntheticEvent` and
  `ModifiedLife` rows for the payment `MUST` carry the row's
  `affectorId`.
- <a id="ReplacementEffect.etb-choice-bracket"></a>**ReplacementEffect.etb-choice-bracket.**
  On the pay-life entry branch, the persistent row `MUST` be deleted through
  `diffDeletedPersistentAnnotationIds` in the GSM that moves the object onto
  the battlefield.

## Open points

- What selects the transient or the persistent rail for a given emission?
- What do `replacedEffectRecipient`, `replacedEffectSource` and
  `replacementEffectSpawnerType` identify on each branch, and why do some rows
  use a lifecycle identity rather than a card as `affectorId`?
- What does a row whose `affectedIds` names a player represent?
- What decides whether a replaced event emits `ReplacementEffect`,
  `ReplacementEffectApplied`, both or neither?
- Is every choice made before a permanent enters (CR 614.12a), such as an
  optional copy entry, bracketed the way the pay-life entry choice is?
