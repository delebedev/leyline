# Recording Analysis: 2026-02-28_14-15-29

**Proxy capture of real server** — reference recording for protocol conformance.
ETB + counters + aura attachment + mulligan with bottom-card ordering + full blocker declaration + combat damage + SBA death.

**md-frames.jsonl auto-generated on shutdown** (CaptureSink fix confirmed working).

## Game Story

```
Pre-game: ChooseStartingPlayerReq, 3x MulliganReq, GroupReq (promptId=92)
  Put: Creature Enchantment Sheep Glimmer 2/1 (iid 341) — companion/adventure?

T1-T3: Land drops (Plains x3, Island)
T4: Cast Legendary Enchantment (347, grp=?)
    → LayeredEffectCreated (7002)
    → Resolve
T5: Cast Creature Human Cleric 3/3 (355)
    → Resolve
T6: Cast Creature Enchantment Horror 1/1 (360)
    → Resolve
T7: Cast Enchantment Aura (365) targeting Human Cleric
    → SelectTargetsReq (2x)
    → LayeredEffectCreated (7003)
    → AttachmentCreated on Human Cleric (355)
    → DeclareAttackersReq — opponent's Cleric (355) attacks
    → DeclareBlockersReq (6x echo-backs!) — Horror (360) blocks Cleric (355)
    → DamageDealt: Cleric deals 3 to Horror, Horror deals 1 to Cleric
    → SBA_Damage: Horror (360) dies (1 toughness, took 3 damage)

T8: Cast Creature Human Scout 4/4 (370)
    → Resolve
    Cast Enchantment Aura (372) targeting Scout
    → SelectTargetsReq
    → LayeredEffectCreated (7004, 7005)
    → AttachmentCreated on Scout (370)
    → SelectTargetsReq (ETB trigger?)
    → CounterAdded on Scout (370) — counter_type=1 (+1/+1), amount=1
    → PowerToughnessModCreated on Scout (370)
    → LayeredEffectCreated (7006)
    → IntermissionReq (game ends)
```

## New Mechanics vs Catalog

### First time observed (not in previous recordings)

| Mechanic | Count | Catalog status | Notes |
|---|---|---|---|
| **DeclareBlockersReq** | 6 | partial | First real blocker data! 6 echo-backs = iterative toggle pattern confirmed |
| **ChooseStartingPlayerReq** | 1 | missing | Server sends, we don't handle |
| **GroupReq** | 1 | — | Not in catalog. promptId=92. **Mulligan bottom-card ordering** — player mulliganed and chose which card to put on bottom |
| **QueuedGameStateMessage** | 4 | — | Not in catalog. Sent to opponent during interactive prompts |
| **AttachmentCreated** | 4 | wired | First live confirmation. Auras attaching to creatures |
| **CounterAdded** | 2 | wired | counter_type=1 (+1/+1), transaction_amount=1 |
| **PowerToughnessModCreated** | 2 | — | Not in catalog. P/T modification tracking |
| **LayeredEffectCreated** | 10 | missing (nice-to-have) | Heavy usage — buff source tracking from auras/enchantments |
| **SBA_Damage** | 2 | missing | Transfer category for lethal-damage SBA. We emit "Destroy" instead |
| **Put** | 2 | wired | Creature put onto battlefield (companion?) |

### Blocker Protocol (key finding)

Real server blocker sequence:
```
S→C: DeclareBlockersReq (gsId=118, blocker=360 can block attacker=355, maxAttackers=1)
C→S: DeclareBlockersResp (iterative toggle — blocker 360 assigned to attacker 355)
S→C: DeclareBlockersReq echo-back (gsId=119)
C→S: DeclareBlockersResp (toggle again)
S→C: DeclareBlockersReq echo-back (gsId=120)
  ... 6 echo-backs total (player toggling on/off)
C→S: SubmitBlockersReq (finalize — type=113, empty payload)
S→C: GSM with DamageDealt annotations + SBA_Damage zone transfer
```

**Confirms our echo-back fix for both attackers and blockers is correct.** The real server sends a fresh `DeclareBlockersReq` after every iterative `DeclareBlockersResp`.

### DamageDealt annotation shape (reference)

```json
{
  "id": 252,
  "types": ["DamageDealt"],
  "affectorId": 355,       // source (Human Cleric)
  "affectedIds": [360],    // target (Horror)
  "details": {
    "damage": 3,
    "type": 1,             // combat damage
    "markDamage": 1
  }
}
```

Our DamageDealt fires with same structure — damage animation bug may be client-side parsing or missing a companion annotation.

### CounterAdded annotation shape (reference)

```json
{
  "id": 316,
  "types": ["CounterAdded"],
  "affectorId": 374,
  "affectedIds": [370],
  "details": {
    "counter_type": 1,        // +1/+1
    "transaction_amount": 1
  }
}
```

## Catalog Updates Needed

| Item | Current | Should be |
|---|---|---|
| `GroupReq` | Not listed | Add to gre-message-types missing list |
| `QueuedGameStateMessage` | Not listed | Add to gre-message-types (we don't send these during prompts) |
| `PowerToughnessModCreated` | Not in annotation tiers | Add to nice-to-have (annotation type 26 or similar) |
| `SBA_Damage` category | missing (zone-transfers.damage) | Confirmed in real recording — category code for lethal damage SBA |
| `LayeredEffectCreated` | nice-to-have/missing | 10 per game — more common than expected. Consider promoting priority |
| `combat.declare-blockers` notes | "may be clunky" | Update: echo-back pattern confirmed from real server, our fix matches |
| `combat.declare-attackers` notes | No echo mention | Update: echo-back on iterative toggle confirmed and fixed |

## BUGS.md Reconciliation

| Bug | Status |
|---|---|
| Combat targeting (blockers) | **Real data now available** — 6 DeclareBlockersReq, full toggle + submit sequence captured. Our echo-back fix matches real server pattern |
| No combat damage animation | DamageDealt shape matches real server (damage/type/markDamage). Bug persists — may need companion annotation or client-specific detail key format |
| Phase stops broken | Still broken — 142 PhaseOrStepModified |
| AI gray placeholders | Opponent's Human Cleric (grp=75450) rendered by real server — compare our grpId handling for seat 2 |

## Stats

- **479 GRE messages** (302 GSM, 111 Uimessage, 16 SetSettingsResp, 15 ActionsAvailableReq)
- **18 annotation types** used
- **7 transfer categories**: Draw(14), PlayLand(12), CastSpell(12), Resolve(12), Put(2), SBA_Damage(2)
- **14 turns**, 6 spells cast, 1 full combat round with blocking
- No client errors (client-errors.jsonl empty)
