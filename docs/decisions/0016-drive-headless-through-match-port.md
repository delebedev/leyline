---
summary: "ADR: route in-process match drivers through MatchConnection instead of naming MatchSession handlers."
read_when:
  - "changing how tests, acceptance, or simclient submit gameplay responses"
  - "changing MatchConnection.submitGREMessage or session quiescence"
---
# ADR 0016: Route In-Process Drivers Through the Match Connection Port

## Status

Accepted and implemented. MatchFlowHarness, acceptance, and simclient submit
gameplay responses through MatchConnection. No separate Gradle module is needed;
the engine-aware harness remains in the engine harness source set.

## Context

Native and web clients attach through MatchRegistry, MatchConnection, and
MatchOutput. MatchFlowHarness instead called roughly fifteen MatchSession
handlers by name. That duplicated MatchConnection's message-routing table, so
tests could keep passing if the production route diverged.

In-process callers already hold parsed ClientToGREMessage values.
MatchConnection.receive expects an outer service message and parses its GRE
payload. Sending every gameplay response through receive would add an
unnecessary serialize-and-parse cycle.

A gameplay response can also schedule auto-advance or playback after its
immediate handler returns. An in-process caller needs a bounded completion point
before it reads emitted output.

## Decision

MatchConnection exposes submitGREMessage(ClientToGREMessage). It uses the same
processGREMessage routing table as receive without recreating an outer service
message.

After dispatch, submitGREMessage waits for deferred session work scheduled by
that input. The wait uses the session executor as a barrier and never holds
sessionLock. This is server publication quiescence. It is not client
acknowledgement, and it does not include work started later by a timer or another
entrant.

MatchFlowHarness owns a MatchConnection and a MatchOutput adapter. Its gameplay
helpers build ClientToGREMessage values and submit them through the connection.
Acceptance and simclient use those helpers.

The harness remains engine-aware. Its current callers need GameBridge probes,
fixture setup, and focused lifecycle controls. Direct MatchSession calls remain
only for synchronous advancement and controls with no client message.

Registry and test helpers no longer expose MatchSession or GameBridge merely to
submit gameplay responses.

## Consequences

In-process gameplay follows the production routing table. A missing or incorrect
route can fail the existing engine tests instead of being masked by a second
dispatcher.

Callers can read output after the submitted input and its deferred session work
have settled.

The harness stays in engine because its useful boundary includes engine state.
A public consumer module should be introduced only with an adopter whose
classpath or API needs that boundary.

## Alternatives Considered

- Keep direct MatchSession gameplay calls. Rejected because they duplicate
  MatchConnection routing.
- Add a public headless module immediately. Rejected because it had no adopter
  beyond its own tests and duplicated the existing engine-aware harness.
- Drive gameplay through MatchConnection.receive. Rejected because in-process
  callers already have parsed GRE messages.
- Add a new intent protocol over the harness. Rejected because it would duplicate
  existing gameplay message and SimDecision vocabularies.
