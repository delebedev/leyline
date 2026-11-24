---
summary: "Cascade: cast trigger that exiles from the library to a free-cast window; ability ID 86, the cast-permission window, the accepted free cast and the residue's return."
entity: mechanic/Cascade
---
# Cascade

Cascade is a cast trigger. When its source spell is cast, a Cascade ability
goes on the stack; as it resolves it exiles cards from the top of the
library, offers the first eligible card as a free cast through a
cast-permission [ActionsAvailableReq](../prompts/ActionsAvailableReq.md), and
puts the rest on the bottom. The accepted cast is an ordinary
[Cast](../actions/Cast.md) recorded with a type-13
[CastingTimeOption](../annotations/CastingTimeOption.md).

## Rules grounding

Cascade triggers when its source spell is cast. It exiles cards until it
exiles a nonland card with lower mana value than the source spell, lets the
player cast that card without paying its mana cost, then puts every exiled
card not cast on the bottom of the library in a random order (CR 702.85a).
Each instance of Cascade triggers separately (CR 702.85c).

## Surfaces

| Surface | Entity pages | Cascade values |
|---|---|---|
| Keyword stack ability | [Ability](../object_types/Ability.md), [AbilityInstanceCreated](../annotations/AbilityInstanceCreated.md), [TriggeringObject](../annotations/TriggeringObject.md) | `grpId` `86` for every card; `source_zone` `27` |
| Reveal | [ZoneTransfer](../annotations/ZoneTransfer.md), [CardRevealed](../annotations/CardRevealed.md) | Library to Exile, `category` `Exile` |
| Cast-permission window | [ActionsAvailableReq](../prompts/ActionsAvailableReq.md) | `promptId` `1134`; `Cast` and `Pass` |
| Free cast | [Cast](../actions/Cast.md), [CastingTimeOption](../annotations/CastingTimeOption.md), [UserActionTaken](../annotations/UserActionTaken.md) | `abilityGrpId` `86`, `alternativeGrpId` `149`, `type` `13` |
| Residue | [ZoneTransfer](../annotations/ZoneTransfer.md) | Exile to Library, `category` `Put` |

## Trigger lifecycle

1. The GSM that moves the source spell to the stack and records its cast also
   carries `AbilityInstanceCreated` with the source spell as `affectorId`,
   the new ability in `affectedIds` and `source_zone` `27`.
2. The ability object has `grpId` `86`, the source card's `grpId` as
   `objectSourceGrpId`, and the source spell as `parentId`.
3. A persistent `TriggeringObject` row names the ability as `affectorId` and
   the source spell as its affected object, with `source_zone` `27`. It lives
   as long as the ability.

The stack source zone separates Cascade from triggered library reveals such as
`Discover`, whose ability comes from a permanent.

## Reveal and window lifecycle

1. When the Cascade ability resolves, each exiled card gets an
   `ObjectIdChanged` and a Library-to-Exile `ZoneTransfer` with `category`
   `Exile`, all with the Cascade ability as `affectorId`. No `ResolutionStart`
   precedes them.
2. Revealed cards that will not be cast can carry persistent `CardRevealed`
   rows with the Cascade ability as `affectorId`.
3. The same batch ends with a cast-permission `ActionsAvailableReq`:
   `promptId` `1134`, one `CardId` parameter naming the source spell, and
   `allowCancel` `No`.
4. The window offers one `Cast` and `Pass`. The `Cast` carries:

| Field | Value |
|---|---|
| `instanceId`, `grpId` | The eligible card in Exile. |
| `abilityGrpId` | `86`. |
| `sourceId` | The Cascade ability's instance. |
| `alternativeGrpId` | `149`, the shared without-paying-its-mana-cost ability. |
| `alternativeSourceZcid` | The source spell, the same instance as the `CardId` parameter. |

The window carries no `inactiveActions`: the cards exiled before the eligible
one are not offered as disabled casts.

## Free-cast lifecycle

1. The client submits the offered `Cast`.
2. `ObjectIdChanged` and an Exile-to-Stack `ZoneTransfer` with `category`
   `CastSpell` move the card, with the Cascade ability as `affectorId`. The
   same GSM creates the persistent `CastingTimeOption` row on the new stack
   instance: `type` `13`, `alternateCostGrpId` `149`, `castAbilityGrpId` `86`.
3. A spell with targets asks for them now; the rest of the cast follows in a
   later GSM.
4. The GSM that records the cast carries, in order: `UserActionTaken` with
   `actionType` `1`, `abilityGrpId` `86` and `alternativeGrpId` `149`; an
   `ObjectIdChanged` and Exile-to-Library `ZoneTransfer` with `category` `Put`
   for each remaining exiled card, with the Cascade ability as `affectorId`;
   then `ResolutionComplete` with `grpid` `86` and `AbilityInstanceDeleted`
   for the Cascade ability.

No `Shuffle` row accompanies the random bottom order; the `Put` transfers
carry the resulting order.

## Sibling shapes

`Discover` shares the reveal, the window and the free cast.

| Axis | Cascade | Discover |
|---|---|---|
| Ability `grpId` | `86` for every card | One per card |
| Trigger source | The spell on the stack, `source_zone` `27` | A permanent |
| `CardId` and `alternativeSourceZcid` | The source spell | The source permanent |
| `ResolutionStart` for the ability | Absent | Present |
| `inactiveActions` | Absent | Lists the other exiled cards |
| Declined card | Goes to the bottom with the residue | Goes to the hand |

The values `149` and `13` are shared by every free cast; `86` in
`castAbilityGrpId` is what names Cascade.

## Contract

- <a id="Cascade.ability"></a>**Cascade.ability.** The Cascade trigger `MUST`
  be an ability object with `grpId` `86` created by `AbilityInstanceCreated`
  whose `affectorId` is the source spell's stack instance and whose
  `source_zone` is `27`, in the GSM that moves the source spell to the stack.
- <a id="Cascade.triggering-object"></a>**Cascade.triggering-object.** While
  the Cascade ability is on the stack, a persistent `TriggeringObject` row
  `MUST` name the ability as `affectorId` and the source spell's stack
  instance in `affectedIds`, with `source_zone` `27`.
- <a id="Cascade.reveal-affector"></a>**Cascade.reveal-affector.** Each card
  Cascade exiles `MUST` move Library to Exile with `category` `Exile` and the
  Cascade ability as `affectorId`.
- <a id="Cascade.window"></a>**Cascade.window.** The decision to cast the
  eligible card `MUST` be an `ActionsAvailableReq` with `promptId` `1134`
  whose `CardId` parameter names the source spell's stack instance.
- <a id="Cascade.offer"></a>**Cascade.offer.** The window's `Cast` `MUST`
  carry the exiled card's `instanceId`, `abilityGrpId` `86`, the Cascade
  ability's instance as `sourceId`, `alternativeGrpId` `149`, and the source
  spell's stack instance as `alternativeSourceZcid`.
- <a id="Cascade.no-inactive"></a>**Cascade.no-inactive.** *(provisional)* The
  window `MUST NOT` carry `inactiveActions`.
- <a id="Cascade.cast-option"></a>**Cascade.cast-option.** A card cast through
  Cascade `MUST` carry a persistent `CastingTimeOption` row with `type` `13`,
  `alternateCostGrpId` `149` and `castAbilityGrpId` `86`, created in the GSM
  that moves it to the stack.
- <a id="Cascade.accepted"></a>**Cascade.accepted.** The accepted cast `MUST`
  be recorded by `UserActionTaken` with `actionType` `1`, `abilityGrpId` `86`
  and `alternativeGrpId` `149`.
- <a id="Cascade.residue"></a>**Cascade.residue.** After an accepted cast,
  each other card Cascade exiled `MUST` move Exile to Library with `category`
  `Put` and the Cascade ability as `affectorId`, in the GSM that records the
  cast.
- <a id="Cascade.no-resolution-start"></a>**Cascade.no-resolution-start.**
  *(provisional)* The Cascade ability's resolution `MUST NOT` carry a
  `ResolutionStart` row for the ability.
- <a id="Cascade.complete-with-cast"></a>**Cascade.complete-with-cast.**
  *(provisional)* After an accepted cast, `ResolutionComplete` with `grpid`
  `86` for the Cascade ability `MUST` appear in the GSM that records the
  cast, after its `UserActionTaken`.

## Open points

- What does the pass branch send: in which GSM does the declined card move
  to the library, and does `ResolutionComplete` follow at once?
- When Cascade exiles every card without finding an eligible one, is a window
  sent at all?
- Which revealed cards receive `CardRevealed` rows, and when are those rows
  deleted?
- How are several Cascade triggers from one spell ordered and linked?
