---
summary: "Acceptance workflow for turning direct Forge-AI puzzle solutions into backend-neutral scripted suites."
read_when:
  - "a puzzle has a direct AI solution but fails through the GRE/headless path"
  - "writing acceptance YAML from a puzzle solution"
  - "classifying a headless-pass, native-client-fail acceptance result"
---
# AI-Solved Acceptance

AI-solved acceptance turns a rules-solvable puzzle into a scripted acceptance contract.

Use it when direct Forge AI can find a line, but the same line needs to survive the leyline bridge, prompt adapters, and client-compatible action path.

## Loop

1. Write a focused `.pzl` with a deterministic board and one intended line.
2. Run it directly through Forge AI to prove the puzzle is rules-solvable.
3. Convert the compact solution facts into existing acceptance YAML under `puzzles/sets/*.yaml`.
4. Run `just test-acceptance`, or target one suite/scenario with Gradle properties.
5. Fix bridge, prompt, action, or engine behavior until the scripted suite is green.
6. Reuse the same YAML through native-client acceptance when available; classify failures by layer.

The YAML is the contract. It stays backend-neutral: game intent only, no coordinates, delays, or UI gestures.

Targeted acceptance command:

```bash
./gradlew :engine:testAcceptance \
  -PacceptanceSuites=cost-selection-warmup \
  -PacceptanceScenarios=eaten-alive-sacrifice-lethal
```

## One-Turn Win Fixtures

Default generated fixtures should be short and terminal:

```ini
[metadata]
Goal:Win
Turns:1
```

For one-turn automated runs, allow the engine to execute turn 1 by using a two-turn runtime cap, for example `--max-turns 2`.

Prefer terminal win fixtures when possible. `Goal:Win` gives the direct AI a strong objective, and the final result provides a sharper oracle than "some mechanic happened". The acceptance YAML can still assert the discriminating intermediate state instead of relying only on game end.

## YAML Shape

Use the existing loader schema only. Good generated YAML usually names:

- the action that must be available
- the cast, choice, target, cost payment, or attack sequence
- the smallest state assertions that prove the intended line happened

Example:

```yaml
- id: eaten-alive-sacrifice-lethal
  puzzle: eaten-alive-sacrifice-lethal
  run: cast Eaten Alive by sacrificing Ornithopter, exile Centaur Courser, then attack for lethal
  expect: Ornithopter is sacrificed, Grizzly Bears survives to attack, and the opponent loses
  steps:
    - expect:
        action: { type: cast, card: Eaten Alive }
    - cast: Eaten Alive
    - choose: { cto_id: 1 }
    - target: { side: opponent, zone: battlefield, card: Centaur Courser }
    - select_cost: { zone: battlefield, cards: [Ornithopter] }
    - resolve_stack: {}
    - expect:
        all:
          - zone_contains: { side: ours, zone: graveyard, card: Ornithopter }
          - zone_contains: { side: ours, zone: battlefield, card: Grizzly Bears }
          - zone_contains: { side: opponent, zone: exile, card: Centaur Courser }
    - pass_until:
        prompt: DeclareAttackersReq
        max_passes: 8
    - attack: { cards: [Grizzly Bears] }
    - pass_until:
        winner: ours
        loser: opponent
        max_passes: 8
```

If the YAML needs a concept the loader cannot express, add the smallest backend-neutral verb or condition to `engine/src/test/kotlin/leyline/acceptance/` rather than inventing ad-hoc keys.

## Failure Classification

When direct AI passes but acceptance fails, classify the gap before fixing:

- **Puzzle/spec bug:** the direct line depended on state not encoded in the fixture, or the YAML asserts the wrong fact.
- **Bridge gap:** Forge state or callbacks do not project into the expected prompt/action shape.
- **Prompt/action adapter gap:** an available Forge decision is translated into the wrong GRE response.
- **Engine/client divergence:** headless state and native-client-visible state disagree.
- **Driver gap:** native-client acceptance cannot perform a backend-neutral YAML step yet.
- **Timing/flakiness:** the state is correct but the acceptance runner waits at the wrong boundary.

Fix the lowest layer that owns the mismatch. Do not paper over a bridge or adapter bug with extra YAML steps.

## Adapter Principle

The direct Forge-AI run is the decision oracle. The GRE path should reuse the same Forge-AI choice wherever possible, then translate that choice into the client-compatible response shape.

Do not replace a missing adapter with a bespoke local policy. For example, if direct AI pays a sacrifice cost by choosing a specific Forge card, the GRE `PayCostsReq` handler should consult or recover that Forge-AI cost decision and map the chosen card to a PayCosts id. A heuristic such as "sacrifice the lowest board-value permanent" can make one puzzle green while hiding the actual parity gap.

Good fixes preserve this shape:

- direct AI chooses a Forge action/card/mode
- adapter maps that Forge entity to the current GRE prompt ids
- fallback greedy policy is used only when the Forge-AI consult is unavailable or unsafe
- training puzzles stay discriminating, so the wrong local heuristic still fails somewhere

## Training Probes

Use a small set of distinct failure classes to train and verify adapter fixes. A single puzzle can be solved accidentally by a local heuristic; repeated puzzles for the same prompt gap are useful regression coverage only after the root adapter is fixed.

Policy realization probes:

| Puzzle | Direct expectation | Current GRE gap |
|---|---|---|
| `combat-bypass-unsummon.pzl` | cast `Unsummon` on `Runeclaw Bear`, then attack with `Grizzly Bears` for lethal | Forge-AI cast decision is realized, then GRE passes through `DeclareAttackersReq`; seat 1 loses |
| `overload-mizzium-mortars.pzl` | choose the overload branch so `Mizzium Mortars` damages both opposing creatures | GRE realizes the ordinary targeted branch instead of the overload choice; seat 1 loses |
| `eaten-alive-sacrifice-lethal.pzl` | sacrifice `Ornithopter`, exile `Centaur Courser`, attack with `Grizzly Bears` | `PayCostsReq` greedily selects `Grizzly Bears`; seat 1 loses |

Probe design rules:

- Prefer one clear puzzle per failure class before adding same-class variants.
- Keep class probes discriminating: a wrong local fallback should flip `winnerSeat` / `loserSeat`.
- Keep expendable cards vanilla or triggerless unless the probe is specifically about triggered costs.
- Prefer `Goal:Win` and `Turns:1`; use `--max-turns 2` for one-turn direct/GRE runs.
- Allow longer fixtures only when the mechanic needs them, such as overload probes that need combat damage after the spell turn.
- Evaluate success by `winnerSeat=1` / `loserSeat`, not by `gameOver=true`.

## Native-Client Caveat

Terminal win fixtures can end the match session. That is fine for headless TDD, but repeated native-client iteration may need a puzzle-lab mode that keeps the scene ready for the next fixture. Do not add that mode just to prove a headless contract.
