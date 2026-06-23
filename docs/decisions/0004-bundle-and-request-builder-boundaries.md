---
summary: "ADR: keep BundleBuilder responsible for bundle coordination while focused request builders own pure request proto shapes."
read_when:
  - "working in BundleBuilder, RequestBuilder, CastingTimeOptionsBuilder, or GRE prompt bundle emission"
  - "adding a new request proto shape or changing GSM/request pairing"
  - "deciding whether prompt request code belongs in match handlers or game bundle builders"
---
# ADR 0004: Bundle And Request Builder Boundaries

## Status

Accepted.

## Context

Recent match-layer work split client interaction lifecycles out of
`TargetingHandler`. That made the next boundary clearer: lifecycle handlers
decide when a prompt is needed and how its response advances the game, while the
game bundle package owns outbound protocol construction.

`BundleBuilder` had accumulated two different jobs:

- Coordinating `GameStateMessage` diffs, bundle cursor advancement,
  `pendingMessageCount`, request pairing, message ids, and prompt-specific GSM
  augmentation.
- Constructing standalone request proto shapes such as `SearchReq` and
  `CastingTimeOptionsReq` variants.

The second job is pure proto construction. Keeping it inside `BundleBuilder`
made request-shape changes look like bundle lifecycle changes and kept
session handlers coupled to a broad stateful builder even when they only needed
a request payload.

## Decision

Keep `BundleBuilder` as the owner of bundle and frame coordination:

- Capture and diff game state.
- Advance and invalidate the shared `BundleCursor`.
- Pair GSMs with prompt requests and set `pendingMessageCount`.
- Allocate message/game-state ids through the shared counter.
- Keep prompt-specific GSM augmentation that depends on the just-built frame.

Move pure request proto construction into focused builders:

- `RequestBuilder` owns general interactive request shapes, including
  `SearchReq`.
- `CastingTimeOptionsBuilder` owns modal, optional-cost, mana-type, and
  choose-or-cost `CastingTimeOptionsReq` shapes.

Lifecycle handlers call the focused builder for the request shape, then pass
that request to `BundleBuilder` only when an actual bundle is needed.

Do not extract broad bundle helpers or move prompt-specific GSM augmentation as
part of this boundary. Those paths depend on frame-local state and should stay
with `BundleBuilder` until a separate pressure point proves otherwise.

## Consequences

Request-shape edits have a smaller surface area and no longer imply ownership
of cursor, frame, or message-pairing rules.

`BundleBuilder` remains stateful and protocol-sequencing aware, but its public
surface more directly reflects bundle lifecycle responsibilities.

Adding a new prompt request now has a default home: pure proto shape in the
focused request builder; GSM/request sequencing in `BundleBuilder`; lifecycle
state and response handling in the match-layer handler.
