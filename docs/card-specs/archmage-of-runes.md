# Archmage of Runes — Card Spec

## Identity

- **Name:** Archmage of Runes
- **grpId:** 93743
- **Set:** FDN
- **Type:** Creature — Giant Wizard
- **Cost:** {3}{U}{U}
- **P/T:** 3/6
- **Forge script:** `forge/forge-gui/res/cardsfolder/a/archmage_of_runes.txt`

## Mechanics

| Mechanic | Forge DSL | Forge event | Catalog status |
|----------|-----------|-------------|----------------|
| Static cost reduction (instants/sorceries {1} less) | `S:Mode$ ReduceCost` | **none** (static, applied during cost calc via `CostAdjustment`) | partial (convoke-improvise notes) |
| Triggered draw on instant/sorcery cast | `T:Mode$ SpellCast \| Execute$ TrigDraw` → `SVar:TrigDraw:DB$ Draw` | `GameEventSpellResolved` (fires on resolve, triggers check on cast) | wired (draw + stack resolution) |

## What it does

1. **Static ability:** Instant and sorcery spells you cast cost {1} less to cast. Reduces generic mana only; cannot reduce colored costs.
2. **Triggered ability:** Whenever you cast an instant or sorcery spell, draw a card. Triggers on cast (goes on stack above the spell), resolves before the spell.

## Trace (game 2026-03-30_21-37, seat 1)

Archmage was drawn T10, cast T12, attacked T14/T16/T18. A token copy was created by Self-Reflection on T16 (not Archmage's own mechanic). The triggered draw fired multiple times when instants/sorceries were cast T14 onward.

### Lifecycle

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 221 | 173→340 | Library→Hand | Drawn T10 |
| 281 | 340→401 | Hand→Stack | Cast T12 Main1, 5 Islands tapped ({3}{U}{U}) |
| 283 | 401 | Stack→Battlefield | Resolves, enters with summoning sickness |

### Cost reduction in action

Compared Cast actions in GSM 280 (before Archmage ETB) vs GSM 322 (after Archmage on battlefield, T14):

| Card (iid) | Cost before | Cost after | Delta |
|-------------|-------------|------------|-------|
| Run Away Together (165) | {1}{U} | {U} | Generic 1→0, dropped from array |
| Self-Reflection (325) | {4}{U}{U} | {3}{U}{U} | Generic 4→3 |
| Unsummon (348) | {U} | {U} | No change (no generic to reduce) |
| Inspiration from Beyond (327, flashback) | {5}{U}{U} | {4}{U}{U} | Generic 5→4 |

**Key protocol finding:** The server communicates cost reduction by modifying the `manaCost` array directly on Cast actions. There is no separate annotation or persistent annotation for the reduction — the offered action simply has the reduced cost baked in. When generic cost is reduced to 0, the `ManaColor_Generic` entry disappears from the array entirely.

### Triggered draw

| gsId | What happened |
|------|---------------|
| 326 | `AbilityInstanceCreated` — ability grpId 171655 (Archmage draw trigger) placed on stack after instant/sorcery cast |
| 328 | `ResolutionStart` → `ZoneTransfer` Library→Hand category=Draw → `ResolutionComplete` — draw resolves, ability deleted |

The trigger creates a stack ability object (`GameObjectType_Ability`, grpId=171655, `objectSourceGrpId`=93743, `parentId`=401). Standard AbilityInstanceCreated/Deleted lifecycle.

### Combat

Archmage attacked on T14, T16, T18. Dealt 3 damage each attack. On T18, the token copy (iid=455) also attacked — both dealt 3 damage each (total 6) plus the `SyntheticEvent`/`ModifiedLife` for the lethal -12 life total.

### Key findings

- **Cost reduction is baked into `manaCost` on Cast actions.** No CostReduction annotation type exists in the wire protocol. Leyline must compute reduced costs when building the action list and populate `manaCost` with the already-reduced values.
- **Generic cost of 0 is omitted, not sent as count=0.** When reduction eliminates all generic mana, the `ManaColor_Generic` entry is simply absent from the array.
- **Flashback costs are also reduced.** Inspiration from Beyond's flashback cost was reduced from {5}{U}{U} to {4}{U}{U}, confirming the static applies to alternative costs too.
- **Trigger abilityGrpId = 171655** maps to "Whenever you cast an instant or sorcery spell, draw a card." — standard triggered draw, same pattern as other cast triggers.
- **No Forge GameEvent for cost reduction.** Forge handles `ReduceCost` as a static ability mode in `CostAdjustment.java` — it's computed during cost finalization, not emitted as an event. Leyline must query Forge's cost calculator when building Cast actions.

## Gaps for leyline

1. **Static cost reduction in action builder.** The action mapper must query Forge's cost adjustment system when generating Cast actions, so `manaCost` reflects the reduced cost. Currently unclear if this path exists — `CostAdjustment.java` has the logic but leyline's action builder may bypass it.
2. **Generic-cost-zero omission.** When generic cost is reduced to 0, the action builder must omit the `ManaColor_Generic` entry entirely (not send count=0).
3. **Alternative cost reduction.** Flashback and other alternative costs must also be reduced by static abilities. Verify the cost adjustment path covers `alternativeGrpId` casts.
4. **Cast trigger → draw.** The `SpellCast` trigger itself should work if leyline's triggered ability infrastructure is wired — the Forge engine fires the trigger, creates an ability object, and the existing AbilityInstanceCreated/draw pipeline handles resolution. Verify the trigger fires for the correct spell types (instant/sorcery only, not creatures).

## Tooling feedback

### Commands used

| Command | Worked? | Notes |
|---------|---------|-------|
| `just card-grp "Archmage of Runes"` | Yes | Returned two grpIds (93743 + 95213 — likely two printings) |
| `just card-script "Archmage of Runes"` | Yes | Direct path |
| `just scry-ts trace "Archmage of Runes" --game 2026-03-30_21-37` | Yes | Full lifecycle, card name enrichment worked well |
| `just scry-ts board --game 2026-03-30_21-37 --gsid 322` | Yes | Clear board state with actions |
| `just scry-ts gsm show 322 --game 2026-03-30_21-37 --json` | Yes | Full JSON with actions including manaCost arrays |
| `just scry-ts ability 171655` | Yes | Resolved trigger text immediately |
| `just scry-ts game notes 2026-03-30_21-37` | Yes | Human notes helpful for orientation |

### Missing/awkward

- **Comparing actions across GSMs required manual JSON extraction.** Had to pipe through python to extract Cast actions and compare costs side-by-side. A dedicated diff or comparison would save significant time.
- **No way to filter actions by type.** Would have been useful to say `gsm show --actions-only` or `gsm show --action-type Cast`.

### Wish list

- `gsm diff <gsId1> <gsId2>` — would have made the cost reduction comparison trivial (diff actions at gs=280 vs gs=322)
- `gsm show --actions` — show only the actions array, skip zones/annotations
- Action cost comparison mode — given a card on battlefield, show how it modifies available actions

### Upvote existing wishes

- **Card name resolution in trace output** — Yes, very helpful. Card names inline made the trace immediately readable.
- **`scry ability <id>`** — Yes, resolved trigger text in one command. Essential for understanding what the ability objects represent.
- **`gsm diff`** — Would have been the single most useful addition for this spec. The entire cost reduction investigation was manual JSON comparison.

## Supporting evidence needed

- [ ] Other static cost reducers (e.g. Goblin Electromancer, Baral) — confirm same `manaCost` baking pattern
- [ ] Puzzle: Archmage on battlefield with instant in hand, verify reduced cost is offered and accepted
- [ ] Verify leyline action builder calls Forge `CostAdjustment` when computing `manaCost` for Cast actions
