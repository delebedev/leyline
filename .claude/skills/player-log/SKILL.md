---
name: player-log
description: Use when reading, parsing, or debugging Arena's Player.log — game state extraction, annotation errors, client exceptions, GRE message analysis. Activates whenever Player.log investigation is needed.
user-invocable: false
---

# Player.log Reading

**Location:** `~/Library/Logs/Wizards of the Coast/MTGA/Player.log` (macOS). Overwritten each launch — copy immediately after reproducing a bug.

## Tooling (prefer these)

| Task | Command |
|------|---------|
| Game state + errors | `just scry state` |
| Full JSON state | `just scry state --json` |
| Stream GRE blocks | `just scry stream` (`-f` to follow) |
| Current lobby screen | `just scry scene` |
| HTTP live state | `just scry serve` (port 8091: `/state`, `/errors`, `/scene`) |
| Client errors | `arena errors` |
| Error aggregation | `arena issues` (last N days) |

## Decision tree

1. **Quick state check** → `just scry state`
2. **Client crash / annotation error** → `arena errors`, then check [annotation field requirements](reading-player-logs.md#annotation-field-requirements-from-client-source)
3. **Deep investigation** (specific gsId, full zones/objects) → ad-hoc scripts in [reading-player-logs.md](reading-player-logs.md#key-searches)
4. **Compare server vs client** → debug API `:8090` (`/api/messages`, `/api/game-states`) vs Player.log. See [cross-referencing](reading-player-logs.md#cross-referencing-with-debug-api)

## Tips

- JSON lines are 10K+ chars — pipe through `python3 -m json.tool` or `jq`
- Log includes both seat 1 and seat 2 messages (Familiar connection)
- `[UnityCrossThreadLogger]` prefix = game-relevant cross-thread messages
- GRE message types: `grep -oP '"type":"GREMessageType_\w+"' Player.log | sort | uniq -c | sort -rn`

## Full Reference

See [reading-player-logs.md](reading-player-logs.md) for log structure, extraction scripts, annotation field requirements, and common error patterns.
