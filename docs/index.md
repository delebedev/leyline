# Leyline Docs Index

Quick links for debugging, protocol analysis, and conformance workflows.

## Developer Tools

| Tool | Entry point | Purpose | Docs |
|------|-------------|---------|------|
| `tape` | `just tape` | Recording analysis, proto inspection, conformance | `tools/tape/docs/` |
| `wire` | `just wire` | Front Door frame inspection | `tools/wire/docs/` |
| `arena` | `just arena` | MTGA UI automation | `tools/arena/docs/` |
| `scry` | `just scry` | Game state from Player.log | `.claude/skills/player-log/` |

Each tool has `--help` with examples. Docs co-located in `tools/<name>/docs/`.

## Core Reference

- `catalog.yaml` — mechanic catalog: what works, what's wired, what's missing
- `rosetta.md` — Arena protocol ↔ Forge engine ↔ leyline code translation table
- `systems-map.md` — GRE message coverage, system-level gaps, priority tiers
- `architecture.md` — package wiring, server layout, wire protocol, bridge threading, match lifecycle

## Recording / Protocol Debug

- `annotation-variance.md` — annotation type variance profiler across all recordings
- `.claude/skills/player-log/` — read client/player logs for transport/protocol context
- `wire-format.md` — client frame + protobuf wire format details
- `debug-api.md` — debug panel REST/SSE endpoint reference
- `../tools/tape/docs/cli.md` — recording CLI reference

## Playbooks

- `playbooks/annotation-investigation-playbook.md` — trace unknown annotation type end-to-end
- `playbooks/card-lookup-playbook.md` — grpId/abilityId → card name, investigation workflows
- `playbooks/priority-debugging-playbook.md` — two-layer priority model, stuck states, frozen games
- `playbooks/fd-payload-playbook.md` — FD protocol traffic analysis with `wire` tool
- `playbooks/new-fd-feature-playbook.md` — end-to-end playbook for adding a new Front Door feature
- `playbooks/event-discovery-playbook.md` — discover new MTGA event format screen flows

## Gameplay Semantics

- `actions.md` — action proto shape, ActionType enum, field requirements
- `message-patterns.md` — GRE message sequences, routing, combat flow, prompts
- `priority.md` — Forge engine priority state machine + Arena client priority protocol

## Architecture / Design

- `bridge-threading.md` — two-thread model rules, snapshot timing, counter sync, test debugging
- `decisions/` — architectural decision records

## Game Modes

- `puzzles.md` — puzzle mode: `.pzl` loading, protocol differences, card registration, architecture

## Tracking

- [GitHub Issues](https://github.com/delebedev/leyline/issues) — bugs and tasks
- [Project Board](https://github.com/users/delebedev/projects/1) — roadmap

