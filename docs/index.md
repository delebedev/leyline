# Leyline Docs Index

Quick links for debugging, protocol analysis, and conformance workflows.

## Developer Tools

| Tool | Entry point | Purpose | Docs |
|------|-------------|---------|------|
| `tape` | `just tape` | Recording analysis, proto inspection, conformance | `tools/tape/docs/` |
| `wire` | `just wire` | Front Door frame inspection | `tools/wire/docs/` |
| `arena` | `just arena` | MTGA UI automation | `tools/arena/docs/` |
| `scry` | `just scry` | Game state from Player.log | `reading-player-logs.md` |

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

- `actions.md` — action proto shape, ActionType enum, field requirements
- `message-patterns.md` — GRE message sequences, routing, combat flow, prompts
- `priority.md` — Forge engine priority state machine + Arena client priority protocol
- `annotation-field-notes.md` — per-type annotation investigation notes

## Architecture / Design

- `bridge-threading.md` — two-thread model rules, snapshot timing, counter sync, test debugging
- `bridge-vision.md` — target architecture and direction
- `python-tooling-refactor.md` — Python tooling package split + migration phases
- `decisions/` — architectural decision records

## Game Modes

- `puzzles.md` — puzzle mode: `.pzl` loading, protocol differences, card registration, architecture

## Tracking

- [GitHub Issues](https://github.com/delebedev/leyline/issues) — bugs and tasks
- [Project Board](https://github.com/users/delebedev/projects/1) — roadmap

## Retro

- `retro/` — archived session retrospectives
