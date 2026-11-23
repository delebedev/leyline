---
summary: "GRE message stream: message families, GameStateMessage fields, update and snapshot axes, decision bundling and correlation, seat identity, action and annotation rails, queued state."
read_when:
  - "publishing a GameStateMessage or bundling a decision after it"
  - "choosing update or snapshot type, or rewinding state"
  - "correlating a client response with its request"
  - "filling the GSM action rail or the annotation rails"
entity: stream/Messages
---
# Messages and game state

## Meaning

The GRE stream is an ordered sequence of game states and decisions. A
`GameStateMessage` (GSM) publishes one state as one seat sees it. Decision
messages ask that seat to answer against a state; side-channel messages
update presentation and timers. Client replies are `ClientToGREMessage`
payloads that correlate with the request they answer.

## Message stream

Each server event carries an ordered `greToClientMessages` array. A GSM may
stand alone or be followed by decision messages in the same array.

| Family | Role |
|---|---|
| `GameStateMessage` | Full or incremental state. |
| `QueuedGameStateMessage` | The other seat's still-cancellable commitment, published before a regular GSM closes it. |
| `UIMessage`, `TimerStateMessage`, `TimeoutMessage` | Presentation and timer side channels. No response is expected. |
| Handshake messages | Connection, settings, starting player, mulligan and intermission flow. |
| Decision messages | Typed requests answered by a matching client response; see [prompts](../prompts/README.md). |
| `EdictalMessage` | A client-shaped message submitted on a seat's behalf, carried in `edictMessage`. |

## Game-state fields

| Field | Role |
|---|---|
| `type` | `Full` snapshot or `Diff` against `prevGameStateId`. |
| `gameStateId` | Identity of the published state. |
| `prevGameStateId` | The state this message builds on. |
| `update` | Publication mode; see below. |
| `pendingMessageCount` | Number of decision messages that follow this GSM in the same array. |
| `players` | Per-seat state, including `pendingMessageType`, the response family the seat owes. |
| `turnInfo` | Active player, priority player, decision player, phase and step. |
| `zones`, `gameObjects` | Zone and object state; see [identity and zones](../model/identity-and-zones.md). |
| `diffDeletedInstanceIds` | Game objects removed from the running state. |
| `annotations` | One-shot event rows. |
| `persistentAnnotations` | Durable rows, kept until retired. |
| `diffDeletedPersistentAnnotationIds` | Persistent rows retired by annotation ID. |
| `actions` | Board-wide action affordability; see Action rails. |
| `timers` | Timer pool; see [timers](timers.md). |

State IDs form one chain across regular and queued messages.

## Update and snapshot

`update` and `type` are independent axes.

| `update` | Meaning |
|---|---|
| `SendHiFi` | Intermediate high-fidelity state between decision points. Never carries a pending decision. |
| `SendAndRecord` | A recorded state point. May carry a decision. |
| `Send` | An ordinary state point. May carry a decision. |
| `Undo` | A rollback to an earlier state. |

`SendHiFi` is part of the recipient's own stream. It is not an echo of the
other seat's view.

`Diff` is the normal form. `Full` is used for the opening synchronization and
for undo. An undo sends a complete snapshot rather than a reverse diff:
`gameStateId` keeps advancing while `prevGameStateId` names the restored
state.

## Decision bundling and correlation

1. A GSM establishes `gameStateId` and announces `pendingMessageCount`.
2. Exactly that many decision messages follow it in the same array.
3. `players[].pendingMessageType` names the response family each seat owes.
4. The client's response repeats the request's `gameStateId` and echoes its
   `msgId` as `respId`.

Several decisions pending against one state remain separate messages; their
order in the array is part of the interaction.

## Seat identity

The `ConnectResp` message's `systemSeatIds` contains exactly the recipient's
own seat. On later messages `systemSeatIds` addresses the message and can
name both seats; it is not a new seat assignment.

## Action rails

Actions travel on two rails with different content:

| Rail | Content |
|---|---|
| `GameStateMessage.actions` | `ActionInfo` rows, each with a `seatId`: what either seat could currently do. It is board state, restated whether or not the recipient holds priority. |
| `ActionsAvailableReq.actions` | Bare `Action` rows the recipient may submit now, including `Pass` and, when available, `FloatMana`. |

Priority is expressed by the request, not by withholding the state rail.
Cost changes can therefore appear on the state rail while the recipient does
not hold priority. The request rail is specified by `ActionsAvailableReq`.

## Annotation rails

`annotations` carries one-shot events. `persistentAnnotations` carries
durable state that stays in the game state until its ID is listed in
`diffDeletedPersistentAnnotationIds` or its lifecycle retires it. A type can
appear on either rail; the rail belongs to each emission. Order within the
transient array is specified by [annotation ordering](annotation-ordering.md).

## Queued state

`QueuedGameStateMessage` carries an ordinary GSM payload. It shows the
recipient the other seat's commitment while it can still be cancelled — for
example while that seat selects targets or pays costs for a spell it is
casting. A run of queued states is followed, in the same array, by a regular
GSM that closes the interval.

Queued states are never sent again on the regular rail. A seat's own
commitments travel on the regular rail.

## Forced actions

`EdictalMessage` wraps a `ClientToGREMessage` that the server applied on a
seat's behalf, so the client can reconcile an action it did not submit.

## Contract

- <a id="Messages.gsid-advances"></a>**Messages.gsid-advances.** Every GSM,
  regular or queued, `MUST` carry a `gameStateId` greater than that of every
  earlier GSM on the stream, and `MUST NOT` carry a `prevGameStateId` equal to
  its own `gameStateId`.
- <a id="Messages.pending-count"></a>**Messages.pending-count.** A GSM with
  `pendingMessageCount` N `MUST` be followed in the same
  `greToClientMessages` array by exactly N decision messages, immediately
  after it.
- <a id="Messages.hifi-no-pending"></a>**Messages.hifi-no-pending.** A GSM
  with `update=SendHiFi` `MUST NOT` carry `pendingMessageCount`.
- <a id="Messages.undo-full"></a>**Messages.undo-full.** A GSM with
  `update=Undo` `MUST` be `type=Full`, with a `gameStateId` that advances the
  chain and a `prevGameStateId` naming the restored earlier state.
- <a id="Messages.resp-correlates"></a>**Messages.resp-correlates.** A client
  response answers the request whose `msgId` equals its `respId` and whose
  `gameStateId` equals its own. A response for which either differs `MUST NOT`
  be applied to that request.
- <a id="Messages.connect-seat"></a>**Messages.connect-seat.** The
  `ConnectResp` message's `systemSeatIds` `MUST` contain exactly one value,
  the recipient's seat.
- <a id="Messages.action-rail-envelope"></a>**Messages.action-rail-envelope.**
  Every `GameStateMessage.actions` row `MUST` be an `ActionInfo` with a
  `seatId`, and `MUST NOT` be a `Pass` or `FloatMana` action.
- <a id="Messages.action-rail-opponent"></a>**Messages.action-rail-opponent.**
  *(provisional)* Grounded for mana abilities of permanents: the state rail
  `MUST` list actions the opponent could take with public sources it
  controls, under the opponent's `seatId`.
- <a id="Messages.queued-shape"></a>**Messages.queued-shape.** A
  `QueuedGameStateMessage` payload `MUST` be `type=Diff` with `update=Send`,
  and `MUST NOT` carry `turnInfo`, `pendingMessageCount`, `gameInfo` or
  `teams`.
- <a id="Messages.queued-closure"></a>**Messages.queued-closure.** A run of
  queued states `MUST` chain by `prevGameStateId` and `MUST` be closed in the
  same array by a regular GSM whose `prevGameStateId` is the last queued
  `gameStateId`.
- <a id="Messages.queued-other-seat"></a>**Messages.queued-other-seat.**
  Actor-bearing annotations on a queued state `MUST` belong to the seat other
  than the recipient.

## Open points

- What distinguishes `Send` from `SendAndRecord` on a state that carries no
  decision?
- Does a reconnection resynchronize with a `Full` GSM, and with which
  `update`?
- Which of the other seat's commitments are queued, and which travel only on
  the regular rail?
- When does the server send `EdictalMessage`, and which messages does it wrap?
