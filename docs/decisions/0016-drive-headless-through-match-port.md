---
summary: "ADR: drive headless matches through the GRE match-connection port instead of holding MatchSession and GameBridge."
read_when:
  - "changing how session tests, acceptance, or simclient drive a match"
  - "adding a read to a headless match observation"
  - "deciding whether a test assertion is a client-visible claim or engine state"
  - "extracting proto or protocol-constant modules"
---
# ADR 0016: Drive Headless Matches Through the Match Connection Port

## Status

Proposed. No code has moved.

The mechanism is already demonstrated in-tree. `PuzzleMatchDoorFlowTest`,
`PuzzleLandPlayGsmDumpTest`, and `MatchDoorMulliganFlowTest` drive puzzle
matches through `MatchConnection` over an `EmbeddedChannel`. All eight tests
pass, and the three files reference `GameBridge`, `MatchSession`, and `forge.*`
zero times.

Those tests cover connect, mulligan, pass, and land play. Targeting, combat
declaration, and prompt responses have not been driven through the port. The
mechanism is verified; the surface is not.

## Context

[ADR 0006](0006-single-backbone-core-and-heads.md) gave leyline heads named by
audience over a shared engine. `native` and `web` attach through
`MatchRegistry`, `MatchConnection`, and `MatchOutput`. Neither head references
`MatchSession` or `GameBridge`.

Headless callers attach below that line. `MatchFlowHarness` holds `MatchSession`
and `GameBridge` as fields and reads Forge objects for board state. Five files
under `engine/src/harness` reference `GameBridge`; `web` and `native` reference
it in none.

`MatchFlowHarness` calls roughly fifteen `MatchSession` handlers by name, which
duplicates `MatchConnection`'s routing table. A divergence between the two
tables stays invisible, because specs exercise the harness copy while every real
client exercises the other.

`cardViews()` reads `power`, `toughness`, `isTapped`, `damage`, and summoning
sickness from Forge. `GameObjectInfo` carries all five. A spec asserting tapped
state therefore passes even when the projection omits `isTapped`, so
protocol-fidelity claims made through the harness are not grounded in emitted
wire.

Kotlin's `associateWith` makes the test compilation a friend of the harness
compilation (`engine/build.gradle.kts`). `internal` consequently hides nothing
from specs, so the harness boundary can only be asserted by inspecting compiled
classes rather than enforced by the compiler.

`MatchConnection.receive` parses its GRE payload out of an envelope bytes field.
Driving the port through `receive` costs one serialize round-trip per submitted
action. `processGREMessage(ClientToGREMessage)`, one call below, takes the typed
message and pays nothing.

## Decision

Add a `headless` module that attaches at the port `native` and `web` attach
to: `MatchRegistry`, `MatchConnection`, and `MatchOutput`.

`headless` differs from those two in what sits on the far side of the port.
`native` and `web` relay an external client into it; `headless` supplies the
client in-process, which is also why it can read engine state at all.

`headless` drives matches through the GRE port: `ClientToGREMessage` in,
`List<GREToClientMessage>` out. Promote `MatchConnection.processGREMessage` to
the module boundary so in-process drivers skip the envelope round-trip.
`MessageSink.send(List<GREToClientMessage>)` is already the outbound half and
`ListMessageSink` is already its queueing implementation.

Run-to-quiescence needs no new mechanism. `MatchSession` handlers return after
the engine has produced its output, so a synchronous submit followed by a queue
drain is sufficient. The prototype does exactly this with
`EmbeddedChannel.writeInbound` and `generateSequence { channel.readOutbound() }`.

`headless` exposes two named readers. The **client reader** returns what the
port delivered: accumulated `GameStateMessage` projections, the message stream,
and pending prompts. The **engine reader** returns Forge state and mutates
fixtures. A spec's choice of reader declares whether it claims client-visible
behaviour or engine state.

`headless` depends on `engine` with `implementation`, which removes
`GameBridge`, `MatchSession`, `ListMessageSink`, and Forge from the compile
classpath of every consumer. Enforcement moves from inspection to compilation.

`gre-proto` already carries the generated schema, and `engine`, `native`, and
`web` each depend on it with `implementation`. `headless` re-exports it with
`api` so specs can name the wire types.

The protocol constant tables specs import — `PromptIds`, `ZoneIds`,
`DetailKeys`, `KeywordAbilityIds`, `AnnotationConstants` — still sit in
`engine` and need the same extraction.

Delete `HeadlessMatchBoundaryTest` once the compiler enforces the same rule.

## Consequences

Specs exercise the dispatch every real client uses, because the port routes by
message type instead of the caller naming a handler. A routing bug then fails a
spec instead of passing silently.

Client-visible assertions become reproducible by `native` and `web`, because all
three attach to the same port and observe the same emitted messages. Assertions needing Forge state
stay possible and stay marked as such.

14 of 113 migrated specs use a read the port does not serve today: `getCounters`
(8 specs), `hasSVar` and `sVars`, `currentStateName`, own-library contents, and
fixture mutation through `MatchSetup`. Those specs keep the engine reader.
Counters travel on the wire as `CounterAdded` and `CounterRemoved` annotations
rather than as an object field, so a client could derive them; the accumulator
does not do so today.

The remaining 265 reads — 206 zone reads and 59 life reads — are served by
`GameObjectInfo`, `ZoneInfo`, and `PlayerInfo` fields the projection already
emits.

Per-test time does not change measurably. Three envelope-driven tests ran in
13.8 s and two session-tier tests ran in 13.9 s, both dominated by
card-database initialization.

`MatchFlowHarness.passPriority` reads `bridge.actionBridge(seatId).getPending()`
to choose between a sync-only advance and a Pass action. A client makes that
choice from the `ActionsAvailableReq` it received. This read moves behind the
engine reader or is replaced before the client reader is port-only.

Three handles keep the seam clean by convention rather than construction and
need separate closure: `MatchRegistry.getBridge()`,
`MatchRegistry.activeSession()`, and `MatchHandler.session`.

Specs pass puzzle text inline; the port takes a puzzle file path through
`RuntimeMatchConfig`. Either the port grows an inline source or `headless`
writes a temporary file per spec.

Predicted, not measured: session-tier suite wall-clock improves, because the
current observation walks every zone of both seats and rebuilds an accumulator
snapshot per card on each read.

## Alternatives Considered

- **Value protocol over the harness** — a `HeadlessMatch` interface plus
  `MatchIntent`, `PlayAction`, `PromptResponse`, and `AdvanceGoal` sum types,
  with consumers submitting intent values. Rejected: the interface has one
  implementation, capabilities that do not fit it are reached by downcasting to
  `MatchFlowHarness`, and the sum types unwrap in a `when` back into the methods
  they came from. The vocabulary also duplicates `SimDecision`, which copilot
  and simclient already share. Callers gain no restriction that the compile
  classpath does not already give.

- **Separate `client` and engine-state modules** — rejected: 86 of 113 specs
  read both, often in one test body. Splitting them divides one mechanic's
  behaviour across two files, and the test tree is currently the only place
  where a mechanic is a single unit.

- **`copilot` and `simclient` as modules** — rejected: `copilot` has one
  in-process consumer, the dev-flagged `CopilotAutopush` field on
  `MatchSession`, and external clients reach it over HTTP through
  `CopilotNativeTransport`. `simclient` has no consumers.

- **Keep the compiled-metadata boundary test** — rejected: it matches type names
  as ASCII substrings in class files against a hand-maintained consumer list,
  and `leyline/testkit` sits outside that list. A `GameBridge` reference added
  to `HeadlessMatchDsl.kt` leaves the test passing.

- **Drive the port through `MatchConnection.receive`** — rejected for in-process
  drivers, because the envelope carries GRE payloads as bytes and every action
  would pay a serialize round-trip. `native` and `web` keep using `receive`,
  since they have bytes on a socket.
