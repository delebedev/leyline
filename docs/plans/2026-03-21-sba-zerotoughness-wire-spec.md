## SBA Zero-Toughness Wire Spec

**Recording:** `recordings/2026-03-21_22-31-46-game3/`
**Session:** game3, 15 turns, Red aggro vs Golgari PvP
**Mechanic:** State-based action — creature dies when toughness reaches 0 (rule 704.5f)
**Trigger card:** Tragic Banshee (grpId 93786), ETB gives all creatures -1/-1 via triggered ability

---

### Summary

The analyzer tagged `sba_zerotoughness x4`. The recording has **2 distinct SBA_ZeroToughness deaths** (gsId 332 and 365), one per Banshee ETB. The "x4" count likely double-counts due to dual-seat GSM replay in the capture.

Also found: **10 SBA_Damage deaths** in the same session (lethal damage SBA). Both are missing from leyline — `SBA_Damage` is not documented in catalog or rosetta at all.

---

### Protocol Shape: SBA_ZeroToughness

#### Category string

```
"SBA_ZeroToughness"
```

Used in the `category` detail key of a ZoneTransfer annotation, exactly like `"Destroy"` or `"Sacrifice"`. This is a **variant of ZoneTransfer annotation (type 1)**, not a dedicated annotation type.

#### Full annotation sequence for one SBA_ZeroToughness death

From gsId=365 (cleanest example — iid 358, grpId 75528, 1/1 creature killed by Banshee's -1/-1):

```
ResolutionStart       affectorId=388 (trigger iid) affectedIds=[388] details={grpid: 175831}
LayeredEffectCreated  affectorId=388               affectedIds=[7004] (layered effect object)
PowerToughnessModCreated affectorId=388            affectedIds=[358] (victim iid)  details={power: -1, toughness: -1}
ResolutionComplete    affectorId=388               affectedIds=[388] details={grpid: 175831}
AbilityInstanceDeleted affectorId=382 (Banshee iid) affectedIds=[388]
LayeredEffectDestroyed affectorId=388              affectedIds=[7004]
ObjectIdChanged       affectedIds=[358]            details={orig_id: 358, new_id: 389}
ZoneTransfer          affectedIds=[389]            details={zone_src: 28, zone_dest: 37, category: "SBA_ZeroToughness"}
AbilityInstanceDeleted affectorId=382              affectedIds=[388]
```

Key observations:
- `ZoneTransfer` has **no `affectorId`** — this is the SBA pattern (no source, rule-driven)
- `ObjectIdChanged` precedes ZoneTransfer as usual (instanceId reallocated for graveyard)
- `LayeredEffectCreated` / `LayeredEffectDestroyed` bracket the trigger resolution — effect is transient (until-end-of-turn; destroyed immediately after SBA fires, same GSM)
- `PowerToughnessModCreated` fires with the delta for the dying creature; `affectedIds` = victim iid (pre-realloc)
- `affectorId` on PTModCreated = the trigger's stack instanceId (not Banshee's instanceId)
- The layered effect object uses iid >= 7000 (7003 in gsId=332, 7004 in gsId=365) — pure client-side tracking object

#### Note on gsId=332 vs gsId=365

gsId=332 shows `PowerToughnessModCreated {power: -13, toughness: -13}` on iid 360 (grpId 75510, base 3/2). This is an anomaly — the base creature had 3/2 but the snapshot delta produced -13/-13. The EffectTracker computes cumulative diff against a stale baseline; this was likely an accumulated modifier divergence from prior states. The `SBA_ZeroToughness` category itself is correct in both cases. Implementation should emit the actual P/T delta from the Forge layer (the ability's -1/-1) rather than reproduce this.

#### Banshee ETB sequence (gsId=328, first entry)

```
ResolutionStart       affectorId=371 (Banshee iid on stack) affectedIds=[371] details={grpid: 93786}
ResolutionComplete    affectorId=371                         affectedIds=[371] details={grpid: 93786}
ZoneTransfer          affectorId=1                           affectedIds=[371] details={zone_src: 27, zone_dest: 28, category: "Resolve"}
AbilityInstanceCreated affectorId=371                        affectedIds=[377] details={source_zone: 28}
PlayerSelectingTargets affectorId=1                          affectedIds=[377]
```

The Banshee ETB puts its triggered ability (iid 377, grpId 175831) on the stack. It resolves in a later GSM (gsId=332). The trigger is listed as targeting `PlayerSelectingTargets` — this was auto-resolved (opponent AI), not an interactive target selection.

---

### Protocol Shape: SBA_Damage (bonus finding)

Not in catalog or rosetta. Observed in 10 deaths in this session. Same ZoneTransfer annotation shape:

```
ZoneTransfer affectedIds=[<new_iid>] details={zone_src: 28, zone_dest: <gy>, category: "SBA_Damage"}
```

No affectorId. Precedes no LayeredEffect sequence — fires after combat damage DamageDealt resolves. Pattern from gsId=78:

```
ResolutionStart   (combat ability)
DamageDealt       affectorId=291 affectedIds=[287] details={damage: 2, type: 2, markDamage: 1}
ResolutionComplete
ObjectIdChanged   291 → 293 (attacker resolves to GY)
ZoneTransfer      zone_src=27 zone_dest=37 category="Resolve"  (for the non-permanent ability)
AbilityInstanceCreated  (on stack, different ability)
ObjectIdChanged   287 → 294  (creature victim)
ZoneTransfer      zone_src=28 zone_dest=33 category="SBA_Damage"
```

Zone dest was 33 (P1 GY) for one and 37 (P2 GY) for others — owner-relative graveyard, as expected.

---

### What leyline emits today

`GameEventCollector.visit(GameEventCardChangeZone)` falls through to `GameEvent.ZoneChanged` for BF→GY when it is NOT a legend rule victim, NOT sacrificed, NOT bounced, NOT exiled. The `zoneChangedCategory()` heuristic returns `TransferCategory.Destroy`. So the annotation is:

```
ZoneTransfer details={zone_src: 28, zone_dest: <gy>, category: "Destroy"}
```

Functional: card moves to graveyard correctly. Cosmetic: wrong animation category (`Destroy` instead of `SBA_ZeroToughness` or `SBA_Damage`).

---

### Gap: `SBA_Damage` also missing from catalog

`docs/catalog.yaml` `zone-transfers.damage` notes it as missing with "Category 21" and "Falls back to Destroy animation." The real server uses `"SBA_Damage"` as the category string (not `"Damage"`). Update rosetta and catalog with this.

---

### Implementation path

**For `SBA_ZeroToughness`:**

The challenge is the same as legend rule: `GameEventCardChangeZone` fires BF→GY but carries no reason code. We need to know at collection time whether the death was from toughness ≤ 0.

Two options:

**Option A: Forge fork hook (same pattern as legend rule)**

Add a `GameEventCardZeroToughnessDeath` event (or a marker set, analogous to `legendRuleVictims`) fired from `GameAction.checkStateBased()` before `sacrificeDestroy()` for the `noRegCreats` batch. In `GameEventCollector`, check if the dying card is in the zero-toughness victims set → emit `GameEvent.ZeroToughnessDeath`.

Pros: clean, exact, no heuristics.
Cons: requires forge fork change.

**Option B: P/T snapshot heuristic (no forge change)**

At drain time, if a BF→GY `ZoneChanged` event fires and the card's cached P/T (from `lastPT`) is `toughness ≤ 0`, classify as `ZeroToughness`. Problem: `lastPT` is cleared on zone exit (`lastPT.remove(card.id)` in `visit(CardChangeZone)`), so the cached value is gone by drain time. Would need to preserve last-known toughness before clearing.

Fragile: doesn't distinguish lethal damage (which also reduces effective P/T to 0 from damage accumulation + base).

**Recommended: Option A.** The legend rule pattern works well. Add a `CopyOnWriteArraySet<Int> zeroToughnessVictims` to `InteractivePromptBridge`, populate it in `GameAction.checkStateBased()` (in Forge fork), drain in `GameEventCollector.isLegendRuleVictim`-style check.

**For `SBA_Damage`:**

Same fork approach: mark cards dying to lethal damage SBA. In `GameAction.checkStateBased()`, the `desCreats` collection holds lethal-damage victims. Add a parallel marker set for these.

Or: simpler heuristic — if the creature has `damage >= toughness` at zone-change time, it's an SBA_Damage death. The `lastPT` cache would need to survive the zone exit for one tick. Less clean but avoids another fork extension.

---

### New TransferCategory variants needed

```kotlin
SbaZeroToughness("SBA_ZeroToughness"),
SbaDamage("SBA_Damage"),
```

These would be added to `TransferCategory.kt`. Both are BF→GY transfers with specific category strings.

---

### Catalog update required

Current catalog entries:

```yaml
state-based:
  zero-toughness:
    status: wired
    notes: >
      Engine destroys. ZeroToughness transfer category (20) missing in nexus —
      falls back to generic ZoneTransfer. Functional, less specific annotation.
```

The fallback category is `Destroy` (not `ZoneTransfer`). The engine check works. The annotation gap is cosmetic (wrong animation bucket). Confirm status remains `wired` for the rule itself, but the `zone-transfers.zero-toughness` entry should note the real server string is `"SBA_ZeroToughness"` (not just "ZeroToughness").

Also: add `zone-transfers.damage` noting the real server string is `"SBA_Damage"`.

---

### Gaps checklist (flag for engine-bridge agent)

- [ ] **Forge fork:** In `GameAction.checkStateBased()`, add marker population before `sacrificeDestroy(noRegCreats)` and before `destroy(desCreats)` — parallel to legend rule victim tracking
- [ ] **`InteractivePromptBridge`:** Add `zeroToughnessVictims: MutableSet<Int>` and `lethalDamageVictims: MutableSet<Int>` sets
- [ ] **`GameEventCollector.visit(GameEventCardChangeZone)`:** Add check for zero-toughness victims → `GameEvent.ZeroToughnessDeath`, lethal damage victims → `GameEvent.LethalDamageDeath`
- [ ] **`GameEvent.kt`:** Add `ZeroToughnessDeath` and `LethalDamageDeath` sealed variants
- [ ] **`TransferCategory.kt`:** Add `SbaZeroToughness("SBA_ZeroToughness")` and `SbaDamage("SBA_Damage")`
- [ ] **`AnnotationBuilder.categoryFromEvents()`:** Wire new events to new categories
- [ ] **`docs/rosetta.md`:** Update Table 2 entries for code 20 (real label `"SBA_ZeroToughness"`) and code 21 (real label `"SBA_Damage"`)
- [ ] **`docs/catalog.yaml`:** Update `zone-transfers.zero-toughness` and `zone-transfers.damage` notes with correct category strings
