---
summary: "Endure: a resolution-time choice between +1/+1 counters on a permanent and an N/N Spirit token; the OptionalActionMessage that asks it and the two outcomes."
entity: mechanic/Endure
---
# Endure

Endure N is a keyword action performed by a permanent: its controller either
puts N +1/+1 counters on it or creates an N/N white Spirit creature token.
Endure is part of the effect of an ordinary triggered or activated
[Ability](../object_types/Ability.md). The choice is asked during resolution
with an [OptionalActionMessage](../prompts/OptionalActionMessage.md), not a
modal [CastingTimeOptionsReq](../prompts/CastingTimeOptionsReq.md).

## Rules grounding

To endure N, the permanent's controller creates an N/N white Spirit creature
token unless they put N +1/+1 counters on that permanent (CR 701.63a). Endure
0 does nothing (CR 701.63b). A negative answer chooses the token; it does not
skip the action.

## Surfaces

| Surface | Entity pages |
|---|---|
| Choice | [OptionalActionMessage](../prompts/OptionalActionMessage.md) |
| Counter outcome | `CounterAdded`, `Counter` |
| Token outcome | [Token](../object_types/Token.md), `TokenCreated`, [ZoneTransfer](../annotations/ZoneTransfer.md) |

## Choice lifecycle

1. The ability with endure begins to resolve.
2. Its controller receives an `OptionalActionMessage` with prompt `13976`,
   `allowCancel` `No`, and one `CardId` parameter naming the permanent that
   endures. `sourceId` follows
   [OptionalActionMessage.ability-source](../prompts/OptionalActionMessage.md#OptionalActionMessage.ability-source).
3. An affirmative answer puts the counters on that permanent: `CounterAdded`
   names it, and its `Counter` row carries the new total.
4. A negative answer creates the Spirit token instead. The enduring permanent
   gets no counters.
5. The ability completes with `ResolutionComplete` and
   `AbilityInstanceDeleted`.

## Sibling shapes

A modal choice made while a spell is cast uses `CastingTimeOptionsReq`.
Endure's choice is made as the ability resolves, and both answers perform the
action.

A card can carry a separate optional payment before its endure ability is
created, for example a cast-time option. That decision belongs to the card,
not to endure.

## Contract

- <a id="Endure.choice-prompt"></a>**Endure.choice-prompt.** *(provisional)*
  The endure choice `MUST` be an `OptionalActionMessage` with prompt `13976`
  and `allowCancel` `No`.
- <a id="Endure.choice-card"></a>**Endure.choice-card.** *(provisional)* Its
  only prompt parameter `MUST` be a `CardId` naming the permanent that
  endures.
- <a id="Endure.accept-counters"></a>**Endure.accept-counters.** An
  affirmative answer `MUST` put the counters on the enduring permanent and
  `MUST NOT` create a token.
- <a id="Endure.decline-token"></a>**Endure.decline-token.** A negative answer
  `MUST` create the Spirit token and `MUST NOT` put counters on the enduring
  permanent.

## Open points

- Does endure 0 send the choice?
- When an effect makes a permanent other than the ability's source endure,
  does the parameter still name the enduring permanent?
