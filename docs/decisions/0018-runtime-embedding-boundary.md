---
summary: "ADR: Leyline keeps the native protocol head and reusable runtime while embedding hosts own application-facing concerns."
read_when:
  - "deciding whether browser-facing behavior belongs in Leyline"
  - "changing the in-process match-runtime interface"
---
# ADR 0018: Runtime Embedding Boundary

## Status

Accepted. Supersedes [ADR 0006](0006-single-backbone-core-and-heads.md).

## Context

Browser routes, authentication, persistence, configuration, and transport are
application concerns rather than native-protocol or game-engine concerns.
Keeping them in Leyline gave those concerns two owners and made runtime changes
carry an unrelated application surface.

## Decision

Leyline owns the native protocol head and reusable domain, engine, GRE, draft,
and transport-neutral match-runtime modules. An embedding host owns its HTTP,
WebSocket, authentication, persistence, and configuration while running one
Leyline match runtime in the same JVM and Forge process.

The host may submit immutable launch values and serialized GRE input, then
observe committed frame and result values. Leyline retains the match engine
lifecycle, Forge thread, and engine-internal coordination.

## Consequences

Leyline changes for native-protocol or runtime behavior. Host policy changes
outside this repository. Native and embedding hosts reuse one Leyline engine
implementation without a second rules engine or shared mutable match owner.

## Alternatives Considered

- A network gateway was rejected because it adds transport and deployment
  coordination without separating the process-global Forge runtime.
- A second engine in the embedding host was rejected because rules and match
  lifecycle behavior would drift.
