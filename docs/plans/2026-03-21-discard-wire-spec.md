# Discard Wire Spec

**Recording:** `recordings/2026-03-21_23-30-58/`
**Human seat:** 1
**First observation:** 6 unique discard ZoneTransfer events across 5 distinct gsIds (one double-discard)
**Date:** 2026-03-21

---

## Source Spells

All 6 discards are spell-effect discards. No cleanup-step (hand-size) discards appear in this recording.

| SelectNreq gsId | Discard gsId | Source iid | Source grpId | Source card | Min/Max sel |
|---|---|---|---|---|---|
| 88 | 89 | 295 (on stack) | 93831 | Dreadwing Scavenger | 1 / 1 |
| 137 | 138 | 295 (on stack) | 93831 | Dreadwing Scavenger | 1 / 1 |
| 193 | — | 333 (on stack) | 93756 | Inspiration from Beyond | 1 / 1 |
| 202 | 203 | 295 (on stack) | 93831 | Dreadwing Scavenger | 1 / 1 |
| 263 | 264 | 295 (on stack) | 93831 | Dreadwing Scavenger | 1 / 1 |
| 310 | 311 | 385 (on BF) | 93758 | Kiora, the Rising Tide | **2 / 2** |

gsId 193 (Inspiration from Beyond) prompts discard but the resolution path goes to `ZoneTransfer category=Return` (Graveyard→Hand) at gsId 194 — the player discards one card to return a card from graveyard to hand; not tracked as a separate discard event in the annotation sequence.

The double-discard at gsId 311 (Kiora) fires 2 separate `ZoneTransfer` annotations in the same GSM diff, with 2 separate `ObjectIdChanged` annotations preceding them.

---

## SelectNReq Wire Format (Discard Prompt)

**Critical finding:** the real server uses `context=Resolution_a163` and `listType=Dynamic` for all discard SelectNReq — NOT `Discard_a163` / `Static`.

```
SelectNreq {
  gsId:        <prev gsId>          // prompt is delivered before the discard resolves
  systemSeatIds: [1]                // seat being asked to choose
  promptType:  "SelectNReq"
  promptData: {
    minSel:        <N>              // 1 for single discard, 2 for Kiora effect
    maxSel:        <N>              // same as minSel
    context:       "Resolution_a163"   // *** NOT Discard_a163 ***
    optionContext: "Resolution_a9d7"   // present on all discard prompts
    listType:      "Dynamic"           // *** NOT Static ***
    idType:        "InstanceId_ab2c"
    validationType:"NonRepeatable"
    ids:           [<hand card instanceIds>]  // full hand at time of prompt
    sourceId:      <ability stack iid>        // the spell/ability on stack
    prompt.parameters: [{parameterName:"Parameter", type:"PromptId", promptId:N}]
      // promptId=1 for single-discard; promptId=2 for double-discard (Kiora)
  }
}
```

### Conformance Bug in `RequestBuilder.buildSelectNReq`

Current code (line 151):
```kotlin
.setContext(if (isLegendRule) SelectionContext.Resolution_a163 else SelectionContext.Discard_a163)
.setListType(if (isLegendRule) SelectionListType.Dynamic else SelectionListType.Static)
```

Observed wire (all 6 discard events):
- `context = Resolution_a163` (value 3)
- `listType = Dynamic` (value 2)
- `optionContext = Resolution_a9d7` (value 5) — **not set for non-legend-rule currently**
- `sourceId` = spell/ability stack instanceId — **confirm this is wired**

Fix needed: discard prompts should use the same context/listType/optionContext as legend-rule prompts. Only the `promptId` value inside `prompt.parameters` differs (1 for single, 2 for double).

---

## Full Annotation Sequence (Single Discard)

Example: gsId=89 (Dreadwing Scavenger, seat 1 discards Rise of the Dark Realms)

```
[ObjectIdChanged]     affector=<ability iid>  affected=[<old iid>]
                      details: {orig_id:<old>, new_id:<new>}

[ZoneTransfer]        affector=<ability iid>  affected=[<new iid>]
                      details: {zone_src:31, zone_dest:33, category:"Discard"}

[ResolutionComplete]  affector=<ability iid>  affected=[<ability iid>]
                      details: {grpid:<spell grpId>}

[AbilityInstanceDeleted] affector=<parent iid>  affected=[<ability iid>]
```

zone_src=31 (Hand, seat 1), zone_dest=33 (Graveyard, seat 1) — consistent across all 6 events.

### Double Discard (Kiora, gsId=311)

Two ObjectIdChanged + ZoneTransfer pairs, then one ResolutionComplete + AbilityInstanceDeleted:

```
[ObjectIdChanged]  aff=386  affected=[379]  {orig:379, new:390}
[ZoneTransfer]     aff=386  affected=[390]  {zone_src:31, zone_dest:33, category:"Discard"}
[ObjectIdChanged]  aff=386  affected=[389]  {orig:389, new:391}
[ZoneTransfer]     aff=386  affected=[391]  {zone_src:31, zone_dest:33, category:"Discard"}
[ResolutionComplete] aff=386 affected=[386] {grpid:143924}
[AbilityInstanceDeleted] aff=385 affected=[386]
```

Same affectorId for both ZoneTransfer events (the resolving ability instance).

---

## ZoneTransfer Annotation

```
ZoneTransfer {
  types:       ["ZoneTransfer"]
  affectorId:  <ability instance iid>   // the on-stack ability that caused discard
  affectedIds: [<new card iid>]          // post-ObjectIdChanged iid
  details: {
    zone_src:  31    // Hand (seat 1)
    zone_dest: 33    // Graveyard (seat 1)
    category:  "Discard"
  }
}
```

**TransferCategory string: `"Discard"`** — confirmed matches `TransferCategory.Discard("Discard")` in our code.

---

## No Cleanup-Step Discards Observed

All 6 discard events fire in Main1 or Combat/DeclareAttack phases — spell effects only. No hand-overflow discards at Cleanup. The `cleanup-discard` catalog entry remains `partial` (engine path exists but unconfirmed wire behaviour for that variant).

---

## Code Status

| Area | Status | Notes |
|---|---|---|
| `TransferCategory.Discard` string | correct | `"Discard"` matches wire |
| `ZoneTransfer` annotation structure | correct | ObjectIdChanged + ZoneTransfer + ResolutionComplete sequence matches |
| `GameEvent.CardDiscarded` event | wired | `GameEventCollector` → `AnnotationBuilder` |
| `SelectNReq` context field | **BUG** | uses `Discard_a163`; wire shows `Resolution_a163` |
| `SelectNReq` listType field | **BUG** | uses `Static`; wire shows `Dynamic` |
| `SelectNReq` optionContext field | **BUG** | not set for discard; wire shows `Resolution_a9d7` |
| `SelectNReq` sourceId | wired (verify) | `buildSelectNReq` sets from `sourceEntityId` |
| Double-discard (N=2) | unverified | minSel=maxSel=2, promptId=2 — confirm Kiora-style effects hit this path |

Flag for engine-bridge agent: `RequestBuilder.buildSelectNReq` incorrectly branches on `isLegendRule` to set context/listType — the discard branch uses wrong values. Both legend-rule and discard (and likely all spell-effect SelectN prompts) use `Resolution_a163` / `Dynamic` / `Resolution_a9d7`. The `isLegendRule` branch may only be needed for `sourceId` (which uses a hardcoded sentinel for legend rule vs. the actual spell iid for discard).

---

## catalog.yaml Updates Needed

- `zones.discard`: status `wired`, notes confirmed correct — no change needed
- `cleanup-discard`: status remains `partial` (no cleanup discard observed in this recording)
- Consider adding `spell-discard` sub-entry under `spells` noting the SelectNReq conformance bug
