# ADR 001: Course-Centric vs Event Orchestrator

**Date:** 2026-03-07
**Status:** Accepted
**Context:** Sealed implementation (Epic #24)

## Decision

CourseService owns the event lifecycle directly. Handlers call CourseService methods; CourseService manages state transitions, persistence, and coordinates with matchdoor via lambdas wired at composition time.

## Rejected Alternative

EventService as orchestrator — wraps CourseService + MatchmakingService + PoolGenerator. Handlers call EventService, which coordinates across services.

More "correct" DDD layering, but adds indirection for no benefit. Only one event type (sealed) has real logic today. Constructed events are trivial pass-through courses with no lifecycle.

## Reason

Simplicity. The course state machine is the hard part and should be front-and-center, not wrapped in a coordinator. The current codebase has one bounded context (frontdoor) with one complex feature (sealed). An orchestrator earns its keep when there are multiple complex features with shared coordination logic.

## Revisit When

- **Draft** adds a pick loop (multi-step pool construction before DeckSelect)
- **Tournaments** add bracket logic (course-to-course coordination)
- A third event type needs lifecycle logic beyond what CourseService handles cleanly

At that point, extract an EventService that coordinates CourseService + DraftService + TournamentService.
