---
summary: "Order of annotations inside one GSM: identity before reference, same-object precedence, damage before death, resolution, cast and draw blocks."
read_when:
  - "emitting several annotations in one GameStateMessage"
  - "debugging an animation or identity error caused by annotation order"
entity: stream/AnnotationOrdering
---
# Annotation ordering

## Meaning

The transient `annotations` array of one GSM is an ordered event stream. The
client applies identity changes and animations in array order, so the same
rows in a different order can produce different visible behavior. Order
across GSMs follows the state chain (see [messages](messages.md)); this page
covers order within one array.

Rows that do not apply to an interaction are left out without changing the
relative order of the rows that remain. Unrelated rows may sit between them.

## Identity before reference

`ObjectIdChanged` comes before any row that refers to its `new_id` in
`affectorId` or `affectedIds`, so the new identity exists before a transfer or
state change uses it. A zone change is normally the pair:

```text
ObjectIdChanged
ZoneTransfer
```

`LayeredEffectCreated` and `ChoiceResult` rows that name the new ID come
before its `ObjectIdChanged`, for example the layered effect of a spell being
cast. `GainDesignation` and `PowerToughnessModCreated` rows can come on either
side of it.

## Incremental changes to one object

When one change updates several aspects of the same object, its rows usually
follow this precedence:

| Order | Annotation | Object identity field |
|---:|---|---|
| 1 | `ControllerChanged` | `affectedIds` |
| 2 | `TappedUntappedPermanent` | `affectedIds` |
| 3 | `DamageDealt` | `affectedIds` |
| 4 | `CounterAdded`, `CounterRemoved` | `affectedIds` |
| 5 | `PowerToughnessModCreated` | `affectedIds` |
| 6 | `LayeredEffectCreated` | `affectorId` |
| 7 | `AttachmentCreated` | `affectorId` |

## Damage and death

Damage rows come before the identity change and zone transfer of permanents
that die from that damage, so the client shows the hit before retiring the
object. A combat-damage block, with every row present:

```text
PhaseOrStepModified
DamageDealt
DamagedThisTurn
SyntheticEvent
ModifiedLife
LayeredEffectDestroyed
ObjectIdChanged
ZoneTransfer
AbilityInstanceDeleted
```

`ModifiedLife` follows the earlier life-changing rows for the same player, so
the client can reconcile the final life total with deltas it has already
shown.

## Spell resolution

A resolving damage spell brackets its effect, then moves itself, then any
permanent that died from its damage:

```text
ResolutionStart
DamageDealt
SyntheticEvent
ModifiedLife
ResolutionComplete
ObjectIdChanged       spell
ZoneTransfer          spell: Stack -> Graveyard
ObjectIdChanged       damaged permanent
ZoneTransfer          damaged permanent: Battlefield -> Graveyard
```

The spell leaves the stack before state-based actions move a permanent its
damage destroyed.

A permanent spell that resolves onto the Battlefield keeps its instance ID
([IdentityAndZones.resolve-keeps-id](../model/identity-and-zones.md#IdentityAndZones.resolve-keeps-id)),
so only its `ZoneTransfer` follows `ResolutionComplete`. When the permanent
needs a choice as it enters, that `ZoneTransfer` can arrive in a later GSM.

## Cast and draw blocks

A targeted cast opens with:

```text
ObjectIdChanged
ZoneTransfer          CastSpell: Hand -> Stack
PlayerSelectingTargets
```

The cast's `UserActionTaken` follows these rows.

A draw-step draw is:

```text
PhaseOrStepModified
ObjectIdChanged
ZoneTransfer          Draw: Library -> Hand
```

## Contract

- <a id="AnnotationOrdering.identity-first"></a>**AnnotationOrdering.identity-first.**
  An `ObjectIdChanged` row `MUST` precede every row in the same array whose
  `affectorId` or `affectedIds` names its `new_id`, except `LayeredEffectCreated`,
  `ChoiceResult`, `GainDesignation` and `PowerToughnessModCreated` rows.
- <a id="AnnotationOrdering.damage-before-death"></a>**AnnotationOrdering.damage-before-death.**
  `DamageDealt` rows `MUST` precede the `ObjectIdChanged` and `ZoneTransfer`
  of a permanent that dies from that damage in the same GSM.
- <a id="AnnotationOrdering.life-last"></a>**AnnotationOrdering.life-last.**
  A player's `ModifiedLife` row `MUST` follow the `DamageDealt` and
  `SyntheticEvent` rows that change that player's life in the same array.
- <a id="AnnotationOrdering.resolution-bracket"></a>**AnnotationOrdering.resolution-bracket.**
  The rows a resolving spell's effect produces `MUST` lie between its
  `ResolutionStart` and `ResolutionComplete`. When the spell leaves the stack
  after resolving, its own `ZoneTransfer` `MUST` follow `ResolutionComplete`,
  preceded by its `ObjectIdChanged` when the move allocates a new ID; a
  permanent spell moving to the Battlefield keeps its ID and has no
  `ObjectIdChanged`. When its own effect moves it, such as a spell that exiles
  itself, those rows lie inside the bracket.
- <a id="AnnotationOrdering.spell-before-sba"></a>**AnnotationOrdering.spell-before-sba.**
  A resolved spell's `ZoneTransfer` off the stack `MUST` precede the
  `ObjectIdChanged` and `ZoneTransfer` of a permanent destroyed by its effect
  through state-based actions.
- <a id="AnnotationOrdering.cast-block"></a>**AnnotationOrdering.cast-block.**
  On a targeted cast, `ObjectIdChanged`, the `CastSpell` `ZoneTransfer` and
  `PlayerSelectingTargets` `MUST` appear in that order.
- <a id="AnnotationOrdering.draw-block"></a>**AnnotationOrdering.draw-block.**
  A draw-step draw `MUST` order the step's `PhaseOrStepModified`, then the
  drawn card's `ObjectIdChanged`, then its `Draw` `ZoneTransfer`.

## Open points

- When one resolution applies several effects to the same object in turn, such
  as a pump and then a tap or two separate counter placements, the rows follow
  the effects' order rather than the precedence table. Is the table order
  required whenever the changes are simultaneous?

- Where do `TokenCreated` rows sit relative to rows that refer to the new
  token?
- When several permanents die in one GSM, in what order are their transfers
  written?
- Where does `PlayerSubmittedTargets` sit relative to the other rows of its
  GSM?
