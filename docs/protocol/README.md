---
summary: "Reference for the GRE message protocol Leyline implements: scope, reading order, normative language, statement IDs and citation rules."
read_when:
  - "emitting, routing or parsing a GRE prompt, action, annotation or game object"
  - "reviewing a change to protocol emission against a written contract"
  - "citing a protocol statement from code, a test, a conformance contract, an issue or a PR"
---
# GRE protocol reference

These pages describe the game-rules-engine (GRE) message protocol that
Leyline's native head implements: the ordered stream of game state, decisions
and responses exchanged during an active game. They are the interoperability
contract Leyline is built against. The project maintains them and revises a
statement when the client is shown to behave differently.

## Scope

In scope: the active-game stream, its shared identity model, and the entities
carried on it — prompts (requests and their responses), actions, annotations,
game objects and designations.

Out of scope: account, lobby, deck, event and matchmaking traffic; client
presentation; and Leyline's internal design. Leyline-defined additions and
deliberate alternatives to the client contract are listed in
[the implementation map](implementation.md), not in entity pages.

An entity page states protocol behavior only. It names no Leyline class, status
or work item. Whether Leyline meets a statement today is answered by
[the implementation map](implementation.md) and by the proof that cites the
statement.

## Reading order

1. This page — vocabulary, normative language and citation rules.
2. The shared model every entity relies on:
   [identity and zones](model/identity-and-zones.md) and
   [enums](model/enums.md).
3. The stream rules: [messages and game state](stream/messages.md),
   [annotation ordering](stream/annotation-ordering.md),
   [priority](stream/priority.md) and [timers](stream/timers.md).
4. The family overview for the entity you are touching:
   [game object types](object_types/README.md), [actions](actions/README.md),
   [designations](designations/README.md), [mechanics](mechanics/README.md),
   [prompts](prompts/README.md) or
   [annotations](annotations/README.md).
5. The entity page — meaning, field reference, lifecycle, then its **Contract**.
6. [The implementation map](implementation.md) — the Leyline owner, status and
   known exceptions for that entity.

Authors also read [the authoring standard](authoring.md). Reviewers follow
[the review guide](../../REVIEW.md), which routes emission changes here.

## Vocabulary

| Term | Meaning |
|---|---|
| GSM | A `GameStateMessage`: one published game state, identified by `gameStateId`. |
| Prompt | A typed request that suspends progression until one seat answers it, plus its response. |
| Annotation | A row describing an event (transient rail, `annotations`) or durable state (persistent rail, `persistentAnnotations`) inside a GSM. |
| Instance ID | The numeric identity of a game object within the current state. A zone change normally allocates a new one. |
| grpId | A card or ability definition identifier from the card database. |
| Seat | A player position, `1` or `2`. |
| Branch | A materially different shape of one entity, selected by its discriminator fields. |
| Statement | One numbered, testable sentence in a page's **Contract** section. |

## Normative language

The keywords `MUST`, `MUST NOT`, `REQUIRED`, `SHALL`, `SHALL NOT`, `SHOULD`,
`SHOULD NOT`, `RECOMMENDED`, `MAY` and `OPTIONAL` are interpreted as described
in RFC 2119 and RFC 8174 when, and only when, they appear in all capitals.
They appear only inside a page's **Contract** section. Everything else on a
page is informative: it explains the contract and gives examples, but adds no
obligation.

Contract statements describe the messages an implementation sends and
accepts. An implementation that emits a request `MUST` produce the shape the
statement describes; an implementation that consumes a response `MUST` accept
every shape the statement allows.

## Provisional statements

A statement marked *(provisional)* is grounded only for the branch, card
family or context it names. Implementations `MAY` rely on it within that scope.
Reviewers `MUST NOT` generalise it: a provisional statement is not evidence that
a sibling branch behaves the same way, and its absence on a sibling branch is
not a defect.

## Open points

Each entity page ends with **Open points**: behavior that is not yet known.
Open points carry no IDs and no obligations. An implementation that must act
where an open point applies chooses the least surprising behavior and records
the choice as an exception in [the implementation map](implementation.md).

## Statement IDs

Every Contract statement carries a stable ID of the form `<Entity>.<slug>`,
matching `^<Entity>\.[a-z0-9-]+$`, for example `SelectNReq.resp-empty-ok`.
`<Entity>` is the page's entity name. The slug names what the statement is
about, not its position, so IDs survive reordering and edits.

- An ID is never reused for a different statement. Rewording a statement keeps
  its ID; changing what it requires retires the old ID and adds a new one.
- Removing or renaming a cited ID fails `just docs-lint` until every citation
  is updated.
- Only falsifiable statements get IDs. [The authoring standard](authoring.md)
  defines that rule.

## Citing a statement

Code, tests, conformance contracts, issues and PR **Evidence** lines cite
statements with the marker `protocol:` followed by one or more IDs:

```kotlin
// protocol: SelectNReq.trigger-order, SelectNReq.trigger-order-arbitrary
```

```yaml
# protocol: ReplacementEffect.etb-choice-bracket
```

```text
Evidence: ShockLandEtbTest fails without the change (protocol: ReplacementEffect.source-zcid).
```

Markdown pages link to a statement's anchor instead:
`[SelectNReq.resp-empty-ok](prompts/SelectNReq.md#SelectNReq.resp-empty-ok)`.

A citation under a test source tree or `conformance/` counts as proof only when
the cited check would fail if the statement were violated. A citation in main
code marks the code that implements the statement; it is not proof.

`just docs-lint` checks that every cited ID exists, that every owner symbol in
[the implementation map](implementation.md) exists in main code, and that
normative keywords stay inside Contract sections. It also lists statements that
no test or conformance contract cites yet. That list is informational and never
fails the build.
