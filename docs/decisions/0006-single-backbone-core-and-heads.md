---
summary: "ADR: leyline is a single stateful backbone — a domain core plus a Forge engine — with two audience-named protocol heads (native, web) over it. The web product's separate gateway is retired into a first-class web head."
read_when:
  - "adding or reshaping a protocol/transport head (native-client or web)"
  - "deciding whether code belongs in domain, engine, a head, or app"
  - "changing the web-profile posture exclusion or the module dependency invariant"
---
# ADR 0006: Single Backbone — Core with Native and Web Heads

## Status

Accepted; implemented.

## Context

Leyline began as one server for one client — the native game client — with the
Forge engine adapter, lobby/account protocols, and persistence growing together
inside a single match module. A browser-based playtesting product needed the
same draft, course, and game runtime. Two obvious paths were both bad: a second
engine inside the web app would drift from the first, and a separate gateway
process embedding leyline would split-brain on Forge's process-global state and
the shared SQLite database. The engine logic was also entangled with the native
client's wire transport, so nothing else could reuse it without dragging that
transport along.

## Decision

Make leyline a single stateful backbone shaped as a domain core with thin
protocol/transport heads:

- **`domain`** — domain model, application services (draft, course, deck,
  collection, matchmaking), and repository port interfaces. No wire, Forge, or
  persistence dependencies.
- **`engine`** — Forge bridge, GRE codec, state/annotation mappers, and the
  transport-agnostic GRE match session. Used by every head; depends only on the
  core, Forge, and proto.
- **`native`** — the head serving the native game client: one web-excluded leaf
  module with internal packages `account`, `frontdoor`, `matchdoor` (auth/boot,
  lobby, and the TCP transport + framing).
- **`web`** — the browser-facing head: HTTP for lobby/draft/deck/collection and a
  single WebSocket that relays the live game in-process to `engine`. Owns its own
  opaque-session user store and publishes an OpenAPI contract; the web client
  repository carries no JVM code beyond the leyline submodule.
- **`app`** — composition root and launch profiles (local, web).

Heads are named by audience, depend on the core, and never on each other. The web
product's previously separate Kotlin gateway is retired; its logic becomes the
`web` head. Ports and TCP framing belong to the transports — `engine` advances a
match from a parsed message and knows nothing about a port.

Build-enforced invariant: nothing depends on `native`, and the **web launch
profile build-excludes `native`**, so the deployed web artifact contains no
native-client or account apparatus. This is a single, testable rule.

## Consequences

Both clients drive the same services, engine, and database — one stateful runtime
owner, no duplicated draft or game logic — so a new front-end is a new head over
the core, not a fork. The posture boundary is one assertion. Forge's
process-global state and single-writer SQLite keep this to one process for now,
which is acceptable; the per-match launch leaves horizontal scaling open without
building it. The native head stays a clean leaf, so it could move to its own
repository later without touching the core.

## Alternatives Considered

- **Second engine in the web app** — rejected: two rules engines drift; the web
  draft would diverge from the native one.
- **Separate gateway process embedding leyline** — rejected: two processes
  contend on Forge's process-global state and the same SQLite — an unguardable
  split-brain.
- **A REST API bolted onto the debug server** — rejected: a real product head
  should not be a graft on a debug surface.
- **Keep account/lobby/game as three native modules** — rejected once the engine
  was shared and a second head existed: the bulk that justified the split moved
  to `engine`, leaving three thin peers better expressed as packages in one head.
