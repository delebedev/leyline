---
summary: "TriggeringObject annotation: persistent link from a triggered ability instance to the object or objects that caused it; source_zone, multi-object and renamed-source branches, birth and retirement."
entity: annotation/TriggeringObject
---
# TriggeringObject

## Meaning

A triggered ability is put on the stack as its own object (CR 603.3). The
client still needs to know which permanent, card or spell the ability came
from, for example to draw an arrow from the stack to the source.
`TriggeringObject` publishes that relation as persistent state for as long as
the ability exists.

The row is selective. Many triggered abilities carry it, but some do not, and
activated abilities never do. Its absence does not prove that an ability is
activated; the [Ability](../object_types/Ability.md) object itself is the
discriminator.

## Variants

Persistent only, never combined with another type.

| Branch | Shape | Example |
|---|---|---|
| Single source | One entry in `affectedIds`, with `source_zone` | An enters-the-battlefield trigger naming its permanent. |
| Source and event objects | Several entries in `affectedIds`, with `source_zone` | Ward naming the targeting spell on the stack and the permanent with ward. |
| Renamed source | `NEW_OBJECT_ID` beside `source_zone` | A dies trigger whose source has already moved to the graveyard under a new instance ID. |
| No zone | No details | Rare; see Open points. |

## Fields

| Field | Role |
|---|---|
| `affectorId` | The triggered ability's instance ID on the stack. |
| `affectedIds` | The object that caused the trigger, or several objects involved in the trigger event. An entry can be an instance ID the object no longer has. |
| `source_zone` | The zone the source was in when the ability triggered, as a zone ID. A leaves-the-battlefield trigger keeps the battlefield zone even after the source has moved. |
| `NEW_OBJECT_ID` | When present, the source's current instance ID after it changed zones. |

## Identity model

`affectorId` is an ability instance; `affectedIds` are card, token or spell
instance IDs. Two triggers from one source get separate rows with distinct
`affectorId` values and the same affected source. An entry of `affectedIds`
can name the source's previous instance ID; follow
[ObjectIdChanged](ObjectIdChanged.md) or `NEW_OBJECT_ID` to its current one
(see [identity and zones](../model/identity-and-zones.md)). `source_zone` uses
the zone IDs of the same page.

## Lifecycle

1. The ability goes on the stack: in one GSM the
   [Ability](../object_types/Ability.md) object appears,
   [AbilityInstanceCreated](AbilityInstanceCreated.md) names it, and the
   `TriggeringObject` row is created.
2. The row stays in `persistentAnnotations` while the ability is on the
   stack, including while its targets are chosen.
3. When the ability resolves or is removed, the row is usually deleted in
   the GSM that carries [AbilityInstanceDeleted](AbilityInstanceDeleted.md)
   for it, which for a resolution is also the GSM with
   [ResolutionComplete](ResolutionComplete.md). A sizeable share of rows
   outlives the ability, some across turns.

## Sibling shapes

[AbilityInstanceCreated](AbilityInstanceCreated.md) is emitted for every
ability instance, activated or triggered, and also names a source and a
`source_zone`. It is transient; `TriggeringObject` is the persistent relation
for triggered abilities that carry one.

## Contract

- <a id="TriggeringObject.persistent"></a>**TriggeringObject.persistent.**
  `TriggeringObject` `MUST` be carried in `persistentAnnotations` and
  `MUST NOT` be combined with another annotation type.
- <a id="TriggeringObject.ability-affector"></a>**TriggeringObject.ability-affector.**
  `affectorId` `MUST` name an `Ability` object.
- <a id="TriggeringObject.born-with-instance"></a>**TriggeringObject.born-with-instance.**
  A row `MUST` first appear in the GSM that carries the
  `AbilityInstanceCreated` naming its `affectorId`.
- <a id="TriggeringObject.source-zone"></a>**TriggeringObject.source-zone.**
  When `source_zone` is present, its value `MUST` be a zone ID from the zone
  layout.

## Open points

- What selects whether a triggered ability carries the row? Phase and step
  triggers, delayed triggers, chapter abilities and Case solve abilities can
  omit it, and identical ability and source pairs occur both with and without
  it.
- Which objects of the trigger event join the source in `affectedIds`, and in
  what order?
- What does a row without `source_zone` represent?
- When is a row deleted with its ability's `AbilityInstanceDeleted`, and
  what deletes a row that outlives the ability?
