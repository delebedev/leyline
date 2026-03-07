# Design Principles

Rules for how code lives inside and across the bounded contexts
(Lobby/FrontDoor, Match, Engine Bridge). Not architecture diagrams —
structure and dependency rules.

## 1. Bounded contexts

Lobby (`frontdoor/`) and Match (`match/`) are separate worlds. Different
invariants, lifecycles, vocabulary. A "deck" in lobby is editable
metadata; in match it's a frozen card list. Cross-BC dependencies go
through interfaces, never concrete types.

The owning context defines the interface. Consumers depend on the
interface, not the implementation. No circular dependencies between
contexts.

## 2. Dependency direction

Dependencies point inward:

    handler → service → domain ← repo interface

Domain depends on nothing. Wire format, persistence, and framework
concerns live at the edges. If a domain type imports Netty or protobuf,
something is wrong.

## 3. Value objects over primitives

`DeckId` not `String`. `Format.STANDARD` not `"Standard"`. Catches bugs
at compile time, self-documenting. `@JvmInline value class` where it's
just a wrapper.

## 4. Repository as boundary

`DeckRepository` interface in the owning context. `SqlitePlayerStore`
implements it. Tests use an in-memory impl. Persistence is an
implementation detail — the domain never sees SQL, JDBC, or file paths.

## 5. Domain objects don't serialize to external wire formats

`@Serializable` is fine for internal persistence (our DB, our schema).
Separate builders handle Arena protocol shapes (V2 summaries, V3
summaries, StartHook) — we don't control those formats and they diverge
from domain structure. Five different wire shapes for `Deck` means no
single serialization annotation works.

## 6. Constructor injection, no singletons

Components declare dependencies in constructors. Wiring happens in
`LeylineServer`. No `PlayerDb.init()` global state, no `object`
singletons holding mutable state. No DI framework — manual constructor
wiring is plenty for this codebase size.

## 7. Forge is the authority

We never duplicate game rules. Our domain is protocol translation and
player data management. The engine thread is sacred — we feed it and
read from it. `GameEvent` originates in Forge; we translate, not
originate.

## 8. Thin handlers, typed domain

Handler parses wire format, calls service with domain types, service
returns domain types, wire layer serializes response. No JSON
construction in handlers. No raw strings crossing service boundaries.
