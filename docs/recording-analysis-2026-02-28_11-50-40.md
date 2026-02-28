# Recording Analysis: 2026-02-28_11-50-40

Session: Sparky game with enchantment exile (Banishing Light), board wipe, self-destroy-enchantment-to-bounce.

## Game Story

```
T1: Play Island, Cast Bird (75485)
T2: Draw Plains, Play Plains
    AI: Play Island, Cast Human Soldier (93852)
T3: Draw Sorcery, Play Plains, Cast Bird Wizard (75479)
T4: Draw Plains, Play Plains, Cast Enchantment (93851 = Banishing Light)
    → SelectTargetsReq → Exile Bird Wizard (75479) [!!! exiled own creature]
    → 7x DeclareAttackersReq (combat phase)
T5: Draw ?, Play Island, Cast Turtle (75465)
T6: Draw Plains, Play Plains
    Cast Sorcery/board-wipe (93853)
    → Destroy Bird (75485), Human Soldier (93852), Turtle (75465)
T7: Draw Instant (93856), Play Plains
    Cast Instant (93856) targeting own Banishing Light (93851)
    → SelectTargetsReq → Destroy Banishing Light (93851)
    → Return Bird Wizard (75479) from Exile → Battlefield
    → IntermissionReq (game over — concede or lethal)
```

## Mechanics Exercised vs Catalog

### Working correctly (confirmed by recording)

| Mechanic | Catalog status | Evidence |
|---|---|---|
| Turn structure | wired | 10 NewTurnStarted, 106 PhaseOrStepModified |
| Priority | wired | 10 ActionsAvailableReq |
| Cast creature | wired | Bird, Human Soldier, Bird Wizard, Turtle |
| Cast enchantment | wired | Banishing Light (93851) cast + resolve |
| Cast sorcery | wired | Board-wipe (93853) |
| Cast instant | wired | Destroy-enchantment (93856) |
| PlayLand | wired | 10 occurrences |
| Draw | wired | 9 occurrences |
| Destroy | wired | 4 occurrences (board wipe + enchantment) |
| Exile | wired | 1 occurrence (Banishing Light ETB) |
| Return | wired | 1 occurrence (Exile → Battlefield on enchantment death) |
| Resolve | wired | 7 occurrences |
| Targeting (spell) | wired | 4 SelectTargetsReq (2 pairs: Banishing Light + Instant) |
| DamageDealt | wired | 2 occurrences (combat) |
| ModifiedLife | wired | 2 occurrences |
| TappedUntapped | wired | 38 occurrences |
| ManaPaid | wired | 19 occurrences |
| ResolutionStart/Complete | wired | 8 each |
| AbilityInstanceCreated/Deleted | wired | 20 each |
| DeclareAttackersReq | wired | 7 occurrences |
| Mulligan | partial | 1 MulliganReq |
| Game over | works | 1 IntermissionReq |
| PlayerSelectingTargets | — | 2 occurrences (fires but catalog says "missing") |
| PlayerSubmittedTargets | — | 2 occurrences (fires but catalog says "missing") |

### Gaps exposed by this game

| # | What | Catalog/Bug status | Recording evidence | Action |
|---|---|---|---|---|
| 1 | **Attacker toggle echo-back** | Not in bugs | 7 DeclareAttackersReq but no iterative echo — client can't confirm toggle | **Fixed this session** (sendDeclareAttackersReq echo on DeclareAttackersResp) |
| 2 | **Blocker declaration** | partial (catalog) | Zero DeclareBlockersReq in entire game — AI never attacked into human blockers, or we never reached blocker phase | Needs dedicated test game where AI attacks and human has creatures to block |
| 3 | **Self-targeting works (Banishing Light on own creature)** | Not in catalog | Exile at gs=115 targets iid=304 (Bird Wizard) — player intentionally exiled own permanent. This is valid Magic (BL targets any nonland permanent). Confirms self-targeting path works in engine, but nexus may not highlight own permanents as valid targets in `buildSelectTargetsReq` | Verify `buildSelectTargetsReq` includes own-side permanents when engine says they're valid |
| 4 | **SyntheticEvent still emitting** | suppressed (catalog) | 2 SyntheticEvent annotations in recording | Should be suppressed — check if filter is working |
| 5 | **No attachment annotations** | wired (catalog) | Banishing Light attaches to exiled card — no AddAttachment/Attachment/RemoveAttachment in recording | Banishing Light uses exile (not aura attach), so no attachment expected. Catalog claim "wired" is correct for auras/equipment, not exile-based removal |
| 6 | **grpId 176387 unknown** | — | One object with grpId 176387, no card types | Possibly a token or internal object. Needs CardDb lookup |

### BUGS.md reconciliation

| Bug | Status after this game |
|---|---|
| ~~Instant targeting~~ (fixed) | Confirmed working — 4 SelectTargetsReq fired |
| ~~Cancel during targeting~~ (fixed) | Not exercised this game |
| Stack visuals | Not directly testable from recording |
| Combat targeting (blockers) | Still untested — no blocker scenario in this game |
| ~~Double DeclareBlockersReq~~ (fixed) | Not exercised |
| Phase stops broken | Still broken — 106 PhaseOrStepModified (every phase gets priority) |
| ~~Cancel attacks~~ (fixed) | Not exercised |
| Lands playable on opponent's turn | Not directly testable from recording |
| ~~SyntheticEvent crash~~ (fixed) | 2 still emitting — verify suppression |
| AI gray placeholders | Not testable from recording (visual) |
| Keyword-granting no visual | Not exercised this game |
| **No combat damage animation** (BUGS.md) | **Possibly stale** — 2 DamageDealt annotations fired. May be working now |
| Mischievous Pup targeting | Same root cause as #3 above (target filtering) |
| Autotap prefers dorks | Not exercised (no mana dorks in this deck) |
| Float mana | Not exercised |

### Catalog updates needed

| Item | Current | Should be |
|---|---|---|
| `PlayerSelectingTargets` (92) | missing (protocol-summary) | wired — 2 occurrences in recording |
| `PlayerSubmittedTargets` (93) | missing (protocol-summary) | wired — 2 occurrences in recording |
| `combat.declare-attackers` notes | No mention of echo-back | Add: iterative DeclareAttackersResp echo-back fixed 2026-02-28 |
| `combat.declare-blockers` notes | "blocker targeting may be clunky" | Add: DeclareBlockersResp echo-back fixed 2026-02-28, still untested with live client |
| **No combat damage animation** (BUGS.md) | Open bug | **Not stale** — DamageDealt fires (2x) but client doesn't play damage animation. Annotation shape likely wrong (missing detail keys or wrong format for client's damage parser) |

## Stats summary

- **242 GRE messages** (210 GSM, 10 ActionsAvailableReq, 7 DeclareAttackersReq, 4 SelectTargetsReq, 4 PromptReq)
- **16 annotation types** used (of 94 possible)
- **7 transfer categories** exercised: PlayLand(10), Draw(9), CastSpell(7), Resolve(7), Destroy(4), Exile(1), Return(1)
- **10 turns**, **7 spells cast**, **1 combat round** with attackers
- Cards: Bird, Bird Wizard, Human Soldier, Cat Soldier, Turtle, Enchantment (Banishing Light), Sorcery (board wipe), Instant (enchantment destroy), Plains, Island
