# Leyline Docs Index

Quick links for debugging, reverse-engineering, and conformance workflows.

## Developer Tools

| Tool | Entry point | Purpose | Docs |
|------|-------------|---------|------|
| `tape` | `bin/tape` | Recording analysis, proto inspection, conformance | `tools/tape/docs/` |
| `wire` | `bin/wire` | Front Door frame inspection | `tools/wire/docs/` |
| `arena` | `bin/arena` | MTGA UI automation | `tools/arena/docs/` |
| `scry` | `bin/scry` | Game state from Player.log | `tools/scry/docs/` |

Each tool has `--help` with examples. Docs co-located in `tools/<name>/docs/`.

## Core Reference

- `catalog.yaml` — mechanic catalog: what works, what's wired, what's missing
- `rosetta.md` — Arena protocol ↔ Forge engine ↔ leyline code translation table
- `architecture.md` — package wiring, server layout, wire protocol, bridge threading, match lifecycle

## Recording / Protocol Debug

- `../tools/tape/docs/cli.md` — recording CLI reference (moved to tape)
- `recording-analysis-runbook.md` — end-to-end capture/analyze/golden workflow
- `recording-triage-runbook.md` — new recordings → catalogue what's new → scope implementation
- `annotation-variance.md` — annotation type variance profiler across all recordings
- `golden-tests.md` — per-message golden field coverage tests
- `reading-player-logs.md` — read client/player logs for transport/protocol context
- `wire-format.md` — client frame + protobuf wire format details
- `debug-api.md` — debug panel REST/SSE endpoint reference

## Playbooks

- `playbooks/annotation-investigation-playbook.md` — trace unknown annotation type end-to-end
- `playbooks/card-lookup-playbook.md` — grpId/abilityId → card name, investigation workflows
- `playbooks/priority-debugging-playbook.md` — two-layer priority model, stuck states, frozen games

## Gameplay Semantics

- `action-format.md` — normalized action payload format
- `action-types.md` — action type catalog
- `priority-loop.md` — Forge engine priority state machine (ASCII diagram + PhaseHandler internals)
- `priority-system-analysis.md` — two-layer priority system analysis (engine + session)
- `combat-protocol.md` — combat request/response sequence
- `annotation-field-notes.md` — per-type annotation investigation notes

## Architecture / Design

- `bridge-architecture.md` — GameActionBridge / InteractivePromptBridge design
- `bridge-vision.md` — target architecture and direction
- `decisions/` — architectural decision records

## Game Modes

- `puzzles.md` — puzzle mode: `.pzl` loading, protocol differences, card registration, architecture

## Tracking

- [GitHub Issues](https://github.com/delebedev/leyline/issues) — bugs and tasks
- [Project Board](https://github.com/users/delebedev/projects/1) — roadmap

## Retro

- `retro/` — archived session retrospectives
