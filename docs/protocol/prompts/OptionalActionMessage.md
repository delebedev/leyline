---
summary: "OptionalActionMessage: yes-or-no prompt for an optional effect; source identity, prompt parameters, presentation hints, OptionalActionResp and lifecycle."
entity: prompt/OptionalActionMessage
---
# OptionalActionMessage

`OptionalActionMessage` asks whether the player wants an optional effect to
happen: a "you may" in a resolving spell or ability (CR 609.3), an optional
payment such as a ward cost, or a choice to put a card somewhere. The answer
is yes or no. The request carries no choice domain; what is being decided is
named by its source and its prompt.

Enum values below are written without their type prefix: `Allow_Yes` is
`OptionResponse_Allow_Yes`, `No` is `AllowCancel_No`.

## Request

| Field | Role |
|---|---|
| `systemSeatIds` | The deciding player. |
| `msgId` | Echoed by the response as `respId`. |
| `gameStateId` | The GSM the decision is made against. |
| `prompt` | The instruction for this optional effect, with `CardId` parameters; see below. |
| `allowCancel` | `No`. Declining is an answer, not a cancellation. |

| `optionalActionMessage` field | Role |
|---|---|
| `sourceId` | The object whose effect is optional: the resolving ability on the stack, or the card itself when the effect belongs to a spell or a permanent. |
| `recipientIds` | Objects the effect would act on, when it has any. |
| `optionalActionTypes` | Presentation hints naming the kind of effect, for example `CardMechanicType_ZoneTransfer`, `CardMechanicType_Explore`, `CardMechanicType_Mutate` or `CardMechanicType_PutTopOrBottom`. |
| `highlight` | Extra emphasis for the source. |

The hints vary independently and none changes the response.

## Prompt

The prompt ID is specific to the optional effect, not one value for every
optional action: it is chosen for the instruction the player reads, so
related cards can share one and one card with two optional effects uses two.

Every prompt parameter is `parameterName=CardId` of `type=Number`. The first
names the source card: the card itself when `sourceId` is a card, or the
card the ability came from (its `parentId`) when `sourceId` is an ability.
Further parameters, when present, name other objects the instruction
mentions, usually members of `recipientIds`.

## Candidate identity

`sourceId` and the first `CardId` parameter are different identities when the
optional effect belongs to an ability: the ability object on the stack versus
its source card. They are not interchangeable.

## Response

The client answers with `ClientMessageType_OptionalActionResp`, correlated as
in [the message stream](../stream/messages.md#Messages.resp-correlates).

| Field | Role |
|---|---|
| `optionalResp.response` | `Allow_Yes` to let the effect happen, `OptionResponse_Cancel_No` to decline it. |
| `systemSeatId` | May be present; it carries no additional meaning. |

The response does not repeat the source or the hints.

## Lifecycle

1. A GSM publishes the state in which the optional effect is pending, usually
   with its ability on the stack.
2. `OptionalActionMessage` follows in the same batch, as in
   [the message stream](../stream/messages.md).
3. The client answers yes or no.
4. The next GSM shows the outcome: an accepted effect continues, possibly
   into further prompts such as [PayCostsReq](PayCostsReq.md) or
   [SelectTargetsReq](SelectTargetsReq.md); a declined effect is skipped and
   resolution continues.

## Contract

- <a id="OptionalActionMessage.no-cancel"></a>**OptionalActionMessage.no-cancel.**
  The request `MUST` carry `allowCancel=No` and no `allowUndo`.
- <a id="OptionalActionMessage.card-parameters"></a>**OptionalActionMessage.card-parameters.**
  Every outer prompt parameter `MUST` be `parameterName=CardId` of
  `type=Number`, and there `MUST` be at least one.
- <a id="OptionalActionMessage.source-card"></a>**OptionalActionMessage.source-card.**
  The first `CardId` parameter `MUST` equal `sourceId` when `sourceId` names a
  card, and `MUST` equal the ability's `parentId` when `sourceId` names an
  ability.
- <a id="OptionalActionMessage.ability-source"></a>**OptionalActionMessage.ability-source.**
  When the optional effect belongs to a resolving triggered or activated
  ability, `sourceId` `MUST` be the instance ID of that ability on the stack.
- <a id="OptionalActionMessage.resp-binary"></a>**OptionalActionMessage.resp-binary.**
  The server `MUST` treat `Allow_Yes` as accepting the effect and
  `OptionResponse_Cancel_No` as declining it, and `MUST NOT` treat a decline
  as cancelling the enclosing spell or ability.

## Open points

- Which prompt ID belongs to which optional effect, and is there a generic
  one for effects without their own?
- What does `highlight` emphasise, and when is it sent?
- Is `CardMechanicType_None` in `optionalActionTypes` different from sending
  no hint?
- When the source card is no longer in a tracked zone, which object does the
  first `CardId` parameter name?
