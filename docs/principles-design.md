---
summary: "Code structure rules: bounded contexts, dependency direction, value objects, and separation of concerns."
read_when:
  - "adding a new class or module and deciding where it belongs"
  - "reviewing code for architectural violations"
  - "deciding how to structure cross-context communication"
---
# Design Principles

Rules for how code lives inside and across the bounded contexts. Not architecture diagrams — structure and dependency rules.

## 1. Bounded contexts

The native lobby/account transport, embedding host, and engine are separate worlds. Different invariants, lifecycles, vocabulary. A "deck" in the lobby is editable metadata; in a match it is a frozen card list. Cross-context dependencies go through interfaces, never concrete types.

The owning context defines the interface. Consumers depend on the interface, not the implementation. No circular dependencies between contexts.

## 2. Dependency direction

Dependencies point inward:

    handler → service → domain ← repository interface

Domain depends on nothing. Wire format, persistence, and framework concerns live at the edges. If a domain type imports Netty or protobuf, something is wrong.

## 3. Value objects over primitives

`DeckId`, not `String`. `Format.STANDARD`, not `"Standard"`. Catches bugs at compile time, self-documenting. Use `@JvmInline value class` where the type is just a wrapper.

## 4. Repository as boundary

`DeckRepository` (interface) in the owning context. `SqlitePlayerStore` implements it. Tests use an in-memory implementation. Persistence is an implementation detail — the domain never sees SQL, JDBC, or file paths.

## 5. Domain objects don't serialize to external wire formats

`@Serializable` is fine for internal persistence (our database, our schema). Separate builders handle external wire shapes we don't control. The same `Deck` domain object appears on the wire in several different shapes depending on the request; no single serialization annotation would fit them all. Keep the annotation off the domain type and let the wire layer translate.

## 6. Constructor injection, no singletons

Components declare dependencies in constructors. Wiring happens in `LeylineServer`. No `Thing.init()` global state, no `object` singletons holding mutable state. No DI framework — manual constructor wiring is plenty for this codebase's size.

## 7. Forge is the authority

We do not duplicate game rules. Our domain is protocol translation and player data management. The engine thread is sacred: we feed it and read from it. `GameEvent` originates in Forge; we translate, we do not originate.

## 8. Thin handlers, typed domain

Handler parses wire format, calls service with domain types, service returns domain types, wire layer serializes the response. No JSON construction in handlers. No raw strings crossing service boundaries.
