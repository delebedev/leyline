# Forge Nexus Docs Index

Quick links for debugging, reverse-engineering, and conformance workflows.

## Recording / Protocol Debug

- `recording-cli.md` — day-to-day CLI (`rec-*`, `proto-*`), practical queries
- `recording-analysis-runbook.md` — end-to-end capture/analyze/golden workflow
- `recording-triage-runbook.md` — new recordings → catalogue what's new → scope implementation
- `reading-player-logs.md` — read client/player logs for transport/protocol context
- `wire-format.md` — client frame + protobuf wire format details
- `debug-api.md` — debug panel REST/SSE endpoint reference
- `match-sequence.md` — expected match lifecycle and key message order

## Game Modes

- `puzzles.md` — puzzle mode: `.pzl` loading, protocol differences, card registration, architecture

## Gameplay Semantics

- `action-format.md` — normalized action payload format
- `action-types.md` — action type catalog
- `priority-loop.md` — priority/prompt behavior and turn flow
- `combat-protocol.md` — combat request/response sequence
- `diff-semantics.md` — game-state diff interpretation

## Architecture / Design

- `architecture.md` — package wiring, server layout, wire protocol, bridge threading, match lifecycle
- `bridge-architecture.md` — original bridge design doc
- `bridge-vision.md` — target architecture and direction

## Bugs / Tracking

- `BUGS.md` — known issues and investigations
