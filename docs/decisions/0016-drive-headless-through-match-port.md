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

Accepted and implemented. The `headless` module drives repository-local puzzle
matches through `MatchConnection` without a transport channel. The existing
harness, acceptance executor, and simclient submit gameplay responses through
the same port.

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

Add a `headless` module that attaches to the same port as `native` and `web`:
`MatchRegistry`, `MatchConnection`, and `MatchOutput`.

`headless` differs from those two in what sits on the far side of the port.
`native` and `web` relay an external client into it; `headless` supplies the
client in-process and may later expose bounded engine observations as
headless-owned values.

`headless` drives matches through the GRE port: `ClientToGREMessage` in,
`List<GREToClientMessage>` out. Connection setup still uses
`MatchConnection.receive`, because the outer service messages establish match
identity. Gameplay input uses `MatchConnection.submitGREMessage` and skips the
envelope round-trip.

The outbound half is `MatchOutput`. The in-process client supplies a queueing
`MatchOutput` adapter and drains only its GRE events after each completed input.
`MessageSink` and `ListMessageSink` remain engine-internal session machinery.

Completion is explicit. `MatchConnection.submitGREMessage` waits for deferred
auto-advance and playback work scheduled by the input before returning. The
wait uses the session executor as a barrier and does not hold `sessionLock`.
This is server publication quiescence, not client acknowledgement or a promise
that future timers cannot produce more output.

The public surface exposes both the emitted message batch and a **client
reader** that accumulates projections and pending prompts from those messages.
A bounded **engine reader** exposes headless-owned immutable values and fixture
commands. Its public signatures do not return Forge or engine types. A spec's
choice of reader declares whether it claims client-visible behaviour or engine
state.

`headless` depends on `engine` with `implementation`. Its callable API names
only JDK and GRE schema types, so consumers cannot import `GameBridge`,
`MatchSession`, `MatchOutput`, or Forge through the module.

`gre-proto` already carries the generated schema, and `engine`, `native`, and
`web` each depend on it with `implementation`. `headless` re-exports it with
`api` so specs can name the wire types.

The protocol constant tables shared with consumers — `PromptIds`, `ZoneIds`,
`DetailKeys`, `KeywordAbilityIds`, `AnnotationConstants` — live in `gre-proto`
beside the generated schema. This keeps them available without an engine compile
dependency.

## Consequences

Specs exercise the dispatch every real client uses, because the port routes by
message type instead of the caller naming a handler. A routing bug then fails a
spec instead of passing silently.

Client-visible assertions become reproducible by `native` and `web`, because all
three attach to the same port and observe the same emitted messages. Assertions
that still need Forge state remain engine tests until a value-only reader exists.

`HeadlessClient` serves zone, object, life, and pending-action reads from emitted
messages. Counters travel as annotations rather than object fields, so
counter assertions that need engine truth use `HeadlessEngine` values.

Engine-tier session specs may combine emitted messages with bounded Forge-state
probes through `MatchFlowHarness`. Gameplay responses still enter through
`MatchConnection`; direct session calls remain only for sync-only advancement
and focused lifecycle controls that have no client message.

The former `MatchRegistry.getBridge`, `MatchRegistry.activeSession`, and
`MatchHandler.session` escape handles are removed. `MatchConnection.session`
remains internal for focused engine tests and harness-only lifecycle controls;
it is absent from the public headless API.

Inline puzzle text is written to a temporary puzzle file before connection.
The production port continues to resolve puzzles through `RuntimeMatchConfig`.

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
