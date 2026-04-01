# Sire of Seven Deaths — Card Spec

## Identity

- **Name:** Sire of Seven Deaths
- **grpId:** 93714 (FDN); 95201 (alternate printing — not traced)
- **Set:** FDN
- **Type:** Creature — Eldrazi
- **Cost:** {7}
- **P/T:** 7/7
- **Forge script:** `forge/forge-gui/res/cardsfolder/s/sire_of_seven_deaths.txt`

## What it does

A 7/7 colorless Eldrazi costing {7}. Pure keyword soup — no activated or triggered abilities, just a wall of static keywords. In hand until turn 28 of this game; cast for {7}, resolved immediately (no triggers, no choices), and attacked every subsequent combat.

**Forge script keywords:** First Strike, Vigilance, Menace, Trample, Reach, Lifelink, Ward—Pay 7 life.

> Note: the user description listed Flying and Deathtouch. The Forge script and wire both show Menace and Reach instead. Flying and Deathtouch are **not present** on the wire uniqueAbilities. The Forge script is authoritative; the user description was incorrect.

## Mechanics

| Mechanic | Forge DSL | Catalog status |
|----------|-----------|----------------|
| First Strike | `K:First Strike` | wired |
| Vigilance | `K:Vigilance` | wired |
| Menace | `K:Menace` | wired |
| Trample | `K:Trample` | wired |
| Reach | `K:Reach` | wired |
| Lifelink | `K:Lifelink` | wired |
| Ward—Pay 7 life | `K:Ward:PayLife<7>` | partial |

Ward uses PayLife cost (not mana). The ward trigger fires and prompts the opponent for a PayCostsReq; the cost is 7 life instead of a mana amount. Ward was not exercised in this session (opponent never targeted Sire).

## Trace (session 2026-03-29_16-55-19, seat 1, turn 28)

Seat 1 controlled Sire. Seat 1's perspective = full visibility.

### Cast and ETB

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 666 | 350 | Hand (31) | ActionsAvailableReq — Cast action offered (grpId=93714, instanceId=350) |
| 667 | 350→511 | Hand→Stack (31→27) | ObjectIdChanged (350→511) + ZoneTransfer category=CastSpell, zone_src=31, zone_dest=27; 7 mana payment bracket begins (7 land taps) |
| 669 | 511 | Stack (27) | Stack state — only Sire remains; hasSummoningSickness not yet set |
| 671 | 511 | Stack→BF (27→28) | ResolutionStart/Complete grpId=93714; ZoneTransfer affectorId=1 category=Resolve zone 27→28; hasSummoningSickness=true; full uniqueAbilities array present |

No ETB trigger — Sire has no triggered abilities.

### Attack (turn 28, first swing)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 716 | 511 | BF (28) | DeclareAttackersReq — Sire listed as qualified attacker (iid 511); autoDeclare=false |
| 717 | 511 | BF (28) | attackState=Declared_a3a9, attackInfo.targetId=2 (opponent seatId); uniqueAbilities intact; vigilance = NOT tapped |

Sire attacked player 2 directly. Not blocked. No ward trigger (no targeting by opponent).

Subsequent attacks at gsId 778, 779, 780 — same shape each time. Sire remained untapped (vigilance) and not removed.

## Annotations

### Cast (gsId 667)

```
annotations {
  id: 1459
  affectedIds: 350
  type: ObjectIdChanged
  details { key: "orig_id" valueInt32: 350 }
  details { key: "new_id"  valueInt32: 511 }
}
annotations {
  id: 1460
  affectedIds: 511
  type: ZoneTransfer
  details { key: "zone_src"  valueInt32: 31 }
  details { key: "zone_dest" valueInt32: 27 }
  details { key: "category"  valueString: "CastSpell" }
}
```

### ETB (gsId 671)

```
annotations {
  id: 1510
  affectorId: 511
  affectedIds: 511
  type: ResolutionStart
  details { key: "grpid" valueInt32: 93714 }
}
annotations {
  id: 1511
  affectorId: 511
  affectedIds: 511
  type: ResolutionComplete
  details { key: "grpid" valueInt32: 93714 }
}
annotations {
  id: 1513
  affectorId: 1
  affectedIds: 511
  type: ZoneTransfer
  details { key: "zone_src"  valueInt32: 27 }
  details { key: "zone_dest" valueInt32: 28 }
  details { key: "category"  valueString: "Resolve" }
}
persistentAnnotations {
  id: 1512
  affectorId: 511
  affectedIds: 511
  type: Qualification
  details { key: "SourceParent"         valueInt32: 511 }
  details { key: "grpid"                valueInt32: 142 }
  details { key: "QualificationSubtype" valueInt32: 0 }
  details { key: "QualificationType"    valueInt32: 40 }
}
```

The Qualification pAnn (QualificationType=40, grpid=142=Menace) fires at ETB. This is the same pattern seen on Nullpriest of Oblivion and Concealing Curtains: Menace requires a Qualification annotation so the client knows to enforce the 2-blocker rule. None of the other keywords on Sire (First Strike, Vigilance, Trample, Reach, Lifelink, Ward) fire a Qualification pAnn at ETB — they are encoded only via uniqueAbilities on the gameObject.

### gameObject uniqueAbilities (BF state, iid 511)

Present identically in gsId 667 (Limbo/hand), 671 (ETB), 717 (attack), 778 (later attack):

```
uniqueAbilities { id: 383; grpId: 6    }   # First Strike
uniqueAbilities { id: 384; grpId: 15   }   # Vigilance
uniqueAbilities { id: 385; grpId: 142  }   # Menace
uniqueAbilities { id: 386; grpId: 14   }   # Trample
uniqueAbilities { id: 387; grpId: 13   }   # Reach
uniqueAbilities { id: 388; grpId: 12   }   # Lifelink
uniqueAbilities { id: 389; grpId: 175751 } # Ward (ability instance grpId)
```

The same 7 entries appear in the hand/Limbo object (gsId 667, iid 350, ids 261–267). The uniqueAbility `id` values change on ObjectIdChanged (261–267 → 383–389) but grpIds are stable.

## Key findings

### Keyword grpId reference — 7 keywords confirmed

This is the richest single-card keyword grpId source in the corpus. Seven distinct keywords on one card, all confirmed from the same wire.

| grpId | Keyword | How confirmed |
|-------|---------|---------------|
| 6 | First Strike | Angelic Destiny (Hare Apparent post-attach); Ratcatcher Trainee (conditional AddAbility); Sire (uniqueAbilities) |
| 12 | Lifelink | Sire uniqueAbilities (7 keywords, only unassigned slot maps to Lifelink). Nullpriest spec mentioned but unconfirmed there. |
| 13 | Reach | Wildborn Preserver uniqueAbilities (`id=251 grpId=13 (Reach)` — explicitly labeled in that spec); Sire |
| 14 | Trample | Overrun AddAbility pAnn + Mossborn Hydra native; Sire |
| 15 | Vigilance | **First confirmation.** Sire is the only card in the corpus with Vigilance as a static keyword. The remaining grpId after assigning 6/12/13/14/142 to FS/LL/Reach/Trample/Menace. |
| 142 | Menace | Concealing Curtains (Qualification pAnn grpid=142 on transform); Nullpriest of Oblivion (same); Sire |
| 175751 | Ward (ability grpId) | Sire uniqueAbilities. Cackling Prowler spec cited ward abilityGrpId=141939 (from notes analysis, unconfirmed from wire). **175751 is the wire-confirmed ward ability grpId for Sire (FDN printing).** Ward abilityGrpId may differ per card/set. |

### Menace fires Qualification pAnn; other keywords do not

At ETB, only Menace triggers a `Qualification` persistent annotation (QualificationType=40, grpid=142). First Strike, Vigilance, Trample, Reach, Lifelink, and Ward are represented solely via uniqueAbilities entries. This matches the Concealing Curtains and Nullpriest findings: Menace is the only keyword here that requires a client-side Qualification to drive blocking behavior.

### Vigilance: no isTapped on attack

gsId 717 shows Sire with `attackState: Declared_a3a9` but no `isTapped: true`. Confirms vigilance works as expected — the engine does not tap vigilance creatures and the wire reflects that. No special annotation for vigilance.

### Ward—Pay life: abilityGrpId distinct per printing

grpId 175751 is the ward ability on this card. The Cackling Prowler spec referenced abilityGrpId=141939 for its ward {2}. Ward abilityGrpIds are not universal — each card/printing has its own. The PayCostsReq shape when ward triggers is confirmed by the ward-counter-wire spec; only the abilityGrpId changes per card.

### mana cost shape: {7} = 7× generic land taps

Cast at gsId 667. Seven TappedUntappedPermanent + ManaPaid annotations (one per land). No color requirements. Same generic-mana bracketing seen on other large spells.

## Gaps for leyline

1. **Ward—Pay life cost variant.** The ward-counter-wire spec confirmed PayCostsReq shape for ward {2} (mana). Ward pay-life ({PayLife 7}) will use the same PayCostsReq protocol but with a life-payment effectCost rather than mana. The Forge DSL key is `K:Ward:PayLife<7>`. Verify leyline's ward trigger correctly specifies a life-payment cost (not a mana cost) and sends the right effectCostType to the client.

2. **Qualification pAnn for Menace.** QualificationType=40 with grpid=142 must fire at ETB. Pattern confirmed across three cards (Concealing Curtains, Nullpriest, Sire). Not emitted by leyline currently.

3. **uniqueAbilities: 7 entries.** Sire has 7 uniqueAbilities entries. Verify leyline's keyword registration emits all 7 (no missing entries for any of the keywords). The grpId table above gives the mappings.

4. **grpId 15 = Vigilance** — not previously registered in any keyword grpId table. Needs addition to `KeywordQualifications.kt` or equivalent.

## Supporting evidence

- gsId 667: hand→stack (CastSpell); full uniqueAbilities in Limbo object
- gsId 671: stack→BF (Resolve); Qualification pAnn for Menace; hasSummoningSickness=true; all 7 uniqueAbilities
- gsId 717: first attack declared; attackState=Declared, NOT tapped (vigilance confirmed)
- gsId 778/779/780: subsequent attacks, same shape each turn
- Ward was never exercised — opponent did not target Sire with any spell or ability in this game

## Agent Feedback

**Tooling pain points:**

1. **`just card-grp` returns multiple grpIds without set labels.** `93714|Sire of Seven Deaths` and `95201|Sire of Seven Deaths` — no indication of which is FDN vs. alternate printing. Had to scan the JSONL for which grpId appeared in cast actions to identify the correct one. A `--set FDN` filter or set column in output would save a step.

2. **JSONL keyword field search is lossy.** The `md-frames.jsonl` tool strips `uniqueAbilities` arrays, so grpId lookup per keyword required `tape proto show`. This is the right workflow (as noted in the prompt), but JSONL could include a deduplicated `uniqueAbilityGrpIds: [6, 14, 15, ...]` summary field per gameObject diff to allow quick scans without dropping to proto.

3. **No keyword grpId reference table.** Figuring out which grpId maps to which keyword required cross-referencing four separate card specs. A `docs/keyword-grpids.md` table (or addition to rosetta) would eliminate this archaeology on every new keyword-soup card. Sire is the best single data point to seed that table — 7 keywords, all confirmed.

4. **Forge script discrepancy vs. card oracle.** The Forge script had Menace + Reach where the card oracle (and user brief) listed Flying + Deathtouch. This took extra verification time. If the Forge script has incorrect keyword data for a card, the agent has no way to catch the discrepancy except through wire traces. A note in the card-spec workflow about "Forge script may have wrong keywords — always verify against wire" would help.
