---
summary: "Discover: keyword action that exiles from the library to a free-cast window; per-card ability IDs, triggered and activated sources, the window with inactive reveals, the accepted cast and the residue."
entity: mechanic/Discover
---
# Discover

Discover is a keyword action that a spell or ability can instruct a player to
perform. This page covers discover performed by a triggered or activated
ability of a permanent. The resolving ability exiles cards from the top of the library,
offers the first eligible card as a free cast through a cast-permission
[ActionsAvailableReq](../prompts/ActionsAvailableReq.md), and puts the rest
on the bottom. The accepted cast is an ordinary [Cast](../actions/Cast.md)
recorded with a type-13
[CastingTimeOption](../annotations/CastingTimeOption.md).

## Rules grounding

Discover N exiles cards until a nonland card with mana value N or less is
exiled. The player may cast it without paying its mana cost if the resulting
spell's mana value is N or less; if they don't, the card goes to their hand.
The other exiled cards go on the bottom of the library in a random order
(CR 701.57a).

## Surfaces

| Surface | Entity pages | Discover values |
|---|---|---|
| Discover ability | [Ability](../object_types/Ability.md), [AbilityInstanceCreated](../annotations/AbilityInstanceCreated.md) | One ability `grpId` per card, carrying its N |
| Triggered source link | [TriggeringObject](../annotations/TriggeringObject.md) | `source_zone` `28` |
| Activated source link | [LinkInfo](../annotations/LinkInfo.md) | `LinkType` `2` when the cost sacrifices the source |
| Reveal | [ZoneTransfer](../annotations/ZoneTransfer.md) | Library to Exile, `category` `Exile` |
| Cast-permission window | [ActionsAvailableReq](../prompts/ActionsAvailableReq.md) | `promptId` `1134`; `Cast`, `Pass` and inactive reveals |
| Free cast | [Cast](../actions/Cast.md), [CastingTimeOption](../annotations/CastingTimeOption.md), [UserActionTaken](../annotations/UserActionTaken.md) | The Discover ability, `alternativeGrpId` `149`, `type` `13` |
| Residue | [ZoneTransfer](../annotations/ZoneTransfer.md) | Exile to Library, `category` `Put` |

## Ability lifecycle

1. A triggered Discover ability is created from its permanent with
   `source_zone` `28` and a persistent `TriggeringObject` row from the ability
   to the permanent. An activated one is recorded by `UserActionTaken` with
   `actionType` `2` and the Discover ability in `abilityGrpId`; when its cost
   sacrifices the source, a `LinkInfo` row of `LinkType` `2` links the
   ability to the source's prior instance ID.
2. The ability object carries the card's own Discover ability as `grpId`, so
   the ID differs from card to card.

## Reveal and window lifecycle

1. `ResolutionStart` names the Discover ability with its `grpid`.
2. Each exiled card gets an `ObjectIdChanged` and a Library-to-Exile
   `ZoneTransfer` with `category` `Exile`, with the Discover ability as
   `affectorId`.
3. When the discovered card can be cast, including having legal targets
   when it needs them, the same batch ends with a cast-permission
   `ActionsAvailableReq`:
   `promptId` `1134`, one `CardId` parameter naming the source permanent, and
   `allowCancel` `No`. For a source sacrificed as a cost, the parameter carries
   the permanent's instance ID from before the sacrifice.
4. The window offers one `Cast` and `Pass`. The `Cast` carries:

| Field | Value |
|---|---|
| `instanceId`, `grpId` | The eligible card in Exile. |
| `abilityGrpId` | The Discover ability. |
| `sourceId` | The Discover ability's instance. |
| `alternativeGrpId` | `149`, the shared without-paying-its-mana-cost ability. |
| `alternativeSourceZcid` | The source permanent, the same instance as the `CardId` parameter. |

5. `inactiveActions` lists the other exiled cards: `Play` for a land, `Cast`
   otherwise, each with the card's `instanceId` and `grpId`, the Discover
   ability as `abilityGrpId` and its instance as `sourceId`, and no
   `alternativeGrpId`.

## Free-cast lifecycle

1. The client submits the offered `Cast`.
2. `ObjectIdChanged` and an Exile-to-Stack `ZoneTransfer` with `category`
   `CastSpell` move the card, with the Discover ability as `affectorId`. The
   same GSM creates the persistent `CastingTimeOption` row on the new stack
   instance: `type` `13`, `alternateCostGrpId` `149`, and the Discover ability
   as `castAbilityGrpId`.
3. Targets and payment follow when the spell needs them.
4. The GSM that records the cast carries, in order: `UserActionTaken` with
   `actionType` `1`, the Discover ability in `abilityGrpId` and
   `alternativeGrpId` `149`; the Exile-to-Library `Put` transfers of the
   other exiled cards, with the Discover ability as `affectorId`; then
   `ResolutionComplete` for the Discover ability. A triggered ability's
   `TriggeringObject` row is deleted in that GSM.

## Sibling shapes

[Cascade](Cascade.md) shares the reveal, the window and the free cast. It
differs in its shared ability `86`, its trigger from the spell on the stack,
the absence of `ResolutionStart` and `inactiveActions`, and a declined card
going to the bottom instead of the hand. The values `149` and `13` are shared
by every free cast; the Discover ability in `castAbilityGrpId` is what names
the mechanic.

## Contract

- <a id="Discover.reveal-affector"></a>**Discover.reveal-affector.** Each card
  Discover exiles `MUST` move Library to Exile with `category` `Exile` and the
  Discover ability as `affectorId`.
- <a id="Discover.resolution-start"></a>**Discover.resolution-start.** The
  Discover ability's `ResolutionStart` `MUST` precede its first reveal
  transfer, with the Discover ability's `grpId` as `grpid`.
- <a id="Discover.window"></a>**Discover.window.** When the discovered card
  can be cast, the decision to cast it `MUST` be an `ActionsAvailableReq` with `promptId` `1134`
  whose `CardId` parameter names the source permanent.
- <a id="Discover.offer"></a>**Discover.offer.** The window's `Cast` `MUST`
  carry the exiled card's `instanceId`, the Discover ability as
  `abilityGrpId`, the Discover ability's instance as `sourceId`,
  `alternativeGrpId` `149`, and the source permanent as
  `alternativeSourceZcid`.
- <a id="Discover.inactive-reveals"></a>**Discover.inactive-reveals.**
  *(provisional)* When Discover exiled other cards before the eligible one,
  the window's `inactiveActions` `MUST` list each of them, `Play` for a land
  and `Cast` otherwise, with the Discover ability as `abilityGrpId` and its
  instance as `sourceId`.
- <a id="Discover.cast-option"></a>**Discover.cast-option.** A card cast
  through Discover `MUST` carry a persistent `CastingTimeOption` row with
  `type` `13`, `alternateCostGrpId` `149` and the Discover ability as
  `castAbilityGrpId`, created in the GSM that moves it to the stack.
- <a id="Discover.accepted"></a>**Discover.accepted.** The accepted cast
  `MUST` be recorded by `UserActionTaken` with `actionType` `1`, the Discover
  ability as `abilityGrpId` and `alternativeGrpId` `149`.
- <a id="Discover.residue"></a>**Discover.residue.** After an accepted cast,
  each other card Discover exiled `MUST` move Exile to Library with
  `category` `Put` and the Discover ability as `affectorId`, in the GSM that
  records the cast, before the ability's `ResolutionComplete`.

## Open points

- What does the hand branch send: how is the card moved to the hand, and in
  which GSM does `ResolutionComplete` follow?
- Is the window sent when the discovered card cannot be cast, because the
  resulting spell's mana value is greater than N or it has no legal target,
  or does the card go to the hand without one?
- How does discover performed by a spell differ, with no source permanent to
  name?
- Which source link does an activated Discover ability carry when its cost
  sacrifices nothing?
