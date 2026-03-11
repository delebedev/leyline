# Forge Oracle — Local Mode

## Intent

New debug API endpoint `GET /api/best-play` that asks the running Forge engine "what would you play right now?" Returns recommended card, instanceId, action type, score, and alternatives. Zero mapping needed — Forge IS the engine in local mode.

## Why

Arena automation currently plays cards blindly (scry lists available actions, agent picks heuristically). Forge has `SpellAbilityPicker` — a simulation-based AI that evaluates every legal play and picks the best. Exposing it via HTTP gives the agentic loop an oracle: engine says WHAT to play, OCR says WHERE.

## Key files

- `forge/forge-ai/src/main/java/forge/ai/simulation/SpellAbilityPicker.java` — `chooseSpellAbilityToPlay(SimulationController)` is the entry point. Needs `Game` + `Player`.
- `forge/forge-ai/src/main/java/forge/ai/simulation/GameStateEvaluator.java` — scores board positions.
- `forge/forge-ai/src/main/java/forge/ai/simulation/SimulationController.java` — check what it needs (may need no-op impl).
- `tooling/src/main/kotlin/leyline/debug/DebugServer.kt` — add new route. Has `bridge` field for Forge access.
- `matchdoor/src/main/kotlin/leyline/matchdoor/bridge/` — Forge adapter. Find how to get `Game` and `Player` objects from the running match.
- `matchdoor/CLAUDE.md` — engine adapter architecture, mental model.

## Guidance

1. Trace how DebugServer reaches the Forge game state — follow `bridge` through to the live `Game` object.
2. Instantiate `SpellAbilityPicker(game, player)` where `player` is seat 1 (our player).
3. `SimulationController` — read what it does. May need a simple/default implementation.
4. Serialize result: card name, Forge instanceId, Arena instanceId (from id-map cross-ref), action type (Cast/Play/Activate), evaluation score.
5. Include top 3 alternatives with scores so agent can make informed choices.
6. Don't over-engineer — if SpellAbilityPicker throws on some board states, catch and return empty. Partial oracle > no oracle.
