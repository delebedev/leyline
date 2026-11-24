---
summary: "ShouldntPlay annotation: transient advisory that a legal play or activation is probably a mistake; Reason branches, affector and ability identities, and its by-omission lifetime."
entity: annotation/ShouldntPlay
---
# ShouldntPlay

## Meaning

Some legal actions are rarely what a player wants: playing a land that
enters tapped, casting a second copy of a legendary permanent, activating an
ability whose condition is not met, or crewing a Vehicle that is already a
creature. `ShouldntPlay` lets the client warn about such an action. It is
advisory only: the action stays legal and is still offered.

## Variants

Transient only, never combined with another type. `Reason` selects the
branch:

| `Reason` | Warning | `abilityGrpId` | `affectorId` |
|---|---|---|---|
| `EntersTapped` | The land would enter tapped | absent | The card itself |
| `Legendary` | A legendary permanent with the same name is already on the battlefield | absent | That permanent |
| `ConsequentialConditionNotMet` | A condition the ability depends on is not met | present | The card itself |
| `RedundantActivation` | The activation would have no further effect | present or absent | The card itself |

## Fields

| Field | Role |
|---|---|
| `affectorId` | As above. |
| `affectedIds` | The card whose play or activation is discouraged; on `Legendary`, possibly with a second entry. |
| `Reason` | The branch, as a string. |
| `abilityGrpId` | The ability definition the warning is about, when it concerns one ability. |

## Identity model

On `Legendary`, `affectorId` is the permanent on the battlefield and
`affectedIds` the card in hand, so the two IDs are in different zones.
`abilityGrpId` is a definition, not an ability instance.

## Lifecycle

1. A GSM that offers the action carries the row while the reason applies.
2. Later GSMs carry a new row while the reason still applies.
3. The warning ends by omission. It is never in `persistentAnnotations`.

For example, after a Vehicle is crewed, the GSM can carry
`RedundantActivation` on the Vehicle with the crew ability as
`abilityGrpId`.

## Sibling shapes

[Qualification](Qualification.md) is a lasting rules predicate;
`ShouldntPlay` is a momentary hint about one option. An illegal action is
simply not offered and has no row.

## Contract

- <a id="ShouldntPlay.transient"></a>**ShouldntPlay.transient.**
  `ShouldntPlay` `MUST` be carried in `annotations`, with `Reason` as a string
  detail.
- <a id="ShouldntPlay.self-affector"></a>**ShouldntPlay.self-affector.**
  On `EntersTapped`, `ConsequentialConditionNotMet` and `RedundantActivation`,
  `affectorId` `MUST` equal the single entry of `affectedIds`.
- <a id="ShouldntPlay.legendary-affector"></a>**ShouldntPlay.legendary-affector.**
  On `Legendary`, `affectorId` `MUST NOT` be in `affectedIds`.
- <a id="ShouldntPlay.condition-ability"></a>**ShouldntPlay.condition-ability.**
  On `ConsequentialConditionNotMet`, the row `MUST` carry `abilityGrpId`.

## Open points

- To which seats is each branch sent?
- What selects `abilityGrpId` on `RedundantActivation`?
- What does a second `affectedIds` entry on `Legendary` name?
