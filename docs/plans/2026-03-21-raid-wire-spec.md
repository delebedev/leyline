# Raid Wire Spec

**Session:** `recordings/2026-03-21_22-31-46`
**Cards:** Goblin Boarders (grpId 93800, instanceId 315), Perforating Artist (grpId 93837, instanceId 301)
**Status:** NOT IMPLEMENTED — no Forge hooks, no annotation emitters

---

## Overview

Raid is an ability word (not a keyword), not a distinct annotation type. The two cards here exhibit different raid patterns:

- **Goblin Boarders** — conditional ETB replacement: enters with +1/+1 counter if controller attacked this turn
- **Perforating Artist** — end-step trigger: if controller attacked, each opponent may sac a nonland permanent or discard a card; if they don't, they lose 3 life

There is no "raid annotation" in the protocol. Raid check is handled by the engine; results surface as:
- `CounterAdded` (for Goblin Boarders when raid is active)
- `AbilityInstanceCreated` / `SelectNreq` / `ChoiceResult` / `ModifiedLife` (for Perforating Artist)

---

## Part 1 — Goblin Boarders (ETB conditional counter)

### Observed data

instanceId=315, grpId=93800, cast at gsId=164 (Main1, phase=2 step=0 — before combat).

**Cast sequence (gsId 164, line 310):**
```
ObjectIdChanged   affectedIds=[315]   orig_id=165, new_id=315
ZoneTransfer      affectedIds=[315]   {zone_src:31, zone_dest:27, category:"CastSpell"}
ManaPaid x3       (R, B, gate-hybrid — 1BR total)
UserActionTaken   affectorId=1  affectedIds=[315]  {actionType:1, abilityGrpId:0}
```

**ETB resolution (gsId 166, line 312):**
```
ResolutionStart    affectorId=315  affectedIds=[315]   {grpid:93800}
ResolutionComplete affectorId=315  affectedIds=[315]   {grpid:93800}
ZoneTransfer       affectorId=1    affectedIds=[315]   {zone_src:27, zone_dest:28, category:"Resolve"}
```

**No `CounterAdded` annotation** — raid not active; card was cast before combat.

### When CounterAdded fires

The notes.md header states "CounterAdded when condition met" — this is derived from card text, not from this recording. In this session the player cast Goblin Boarders in Main1 before attacking. **No instance of raid-active ETB was recorded here.**

To observe CounterAdded: need a recording where Goblin Boarders is cast/re-enters after the controller attacked in the same turn (e.g. flash, Second Sunrise effect, or a turn where they attacked in combat and then cast Goblin Boarders post-combat).

### Expected wire spec (not confirmed from recording)

When raid is active (controller attacked this turn), ETB resolution batch should include:

```
ResolutionStart    affectorId=315  affectedIds=[315]  {grpid:93800}
CounterAdded       affectedIds=[315]  {counter_type:<+1/+1 enum>, transaction_amount:1}
Counter            affectedIds=[315]  {counter_type:<+1/+1 enum>, count:1}   ← persistent
ResolutionComplete affectorId=315  affectedIds=[315]  {grpid:93800}
ZoneTransfer       affectorId=1   affectedIds=[315]  {zone_src:27, zone_dest:28, category:"Resolve"}
```

Raid condition check happens in Forge; the counter placement is a normal `GameEventCardCounters` event. No separate "raid fired" annotation.

### Code impact

None currently. When raid ETBs surface in combat: `CounterAdded`/`Counter` are already implemented (type 16/14). Forge counter placement should already emit `GameEventCardCounters` → `CountersChanged` → `counterAdded()` + `counter()`. The raid condition itself is Forge's problem — leyline just needs to not suppress those annotations.

**Verdict: likely works already once a raid-active ETB is exercised.** Needs puzzle to confirm.

---

## Part 2 — Perforating Artist (end-step punisher trigger)

### Card identity

| instanceId | grpId | Card |
|-----------|-------|------|
| 301 | 93837 | Perforating Artist (Battlefield, opponent's) |
| 319 | 175894 | trigger ability instance (grpId 175894 = trigger stack entry) |

The trigger fires 3 times in the session (turns 7, 9, 11 based on phase transitions):
- **Trigger 307** → opponent chose lose 3 life (no creature to sac, 2 options offered)
- **Trigger 319** → opponent chose sacrifice (3 options offered, chose instanceId 309 — grpId 75550)
- **Trigger 329** → opponent chose lose 3 life (3 options offered)

### Full trigger lifecycle (trigger 319 — "sac" path, most complete)

**Step 1 — trigger goes on stack (gsId 183, line 338):**
```
PhaseOrStepModified  affectedIds=[1]  {phase:5, step:0}   ← End step reached (player 1's turn)
AbilityInstanceCreated  affectorId=301  affectedIds=[319]  {source_zone:28}
```
Zone 27 (Stack) now contains objectId 319 (grpId 175894, type=Ability).

**Step 2 — resolution begins (gsId 185, line 340):**
```
ResolutionStart  affectorId=319  affectedIds=[319]  {grpid:175894}
```

**Step 3 — SelectNreq (msgId 237, gsId 185) issued to opponent (seat 2):**
```json
{
  "promptType": "SelectNReq",
  "promptData": {
    "minSel": 1, "maxSel": 1,
    "context": "Resolution_a163",
    "optionContext": "Resolution_a9d7",
    "listType": "Dynamic",
    "ids": [8, 9, 11],
    "prompt": {
      "parameters": [
        {"parameterName":"Parameter", "type":"PromptId", "promptId":1216},
        {"parameterName":"Parameter", "type":"PromptId", "promptId":1217},
        {"parameterName":"Parameter", "type":"PromptId", "promptId":1218}
      ]
    },
    "idType": "PromptParameterIndex",
    "sourceId": 301,
    "validationType": "NonRepeatable"
  }
}
```

Options `ids:[8, 9, 11]` are **PromptParameterIndex values**, not instanceIds. They map to the 3 prompt parameters:
- id=8 → promptId 1216 (sac a nonland permanent)
- id=9 → promptId 1217 (discard a card)
- id=11 → promptId 1218 (lose 3 life)

**Step 4 — opponent selects "sacrifice" (option index 8):**
Second SelectNreq (gsId 186, msgId 239) follows immediately:
```json
{
  "promptData": {
    "ids": [309],
    "idType": "InstanceId_ab2c",
    "sourceId": 301
  }
}
```
This is the creature-selection prompt: choose which permanent to sacrifice. Only one valid target (instanceId 309, grpId 75550).

**Step 5 — resolution batch (gsId 187, line 348):**
```
ChoiceResult       affectorId=301  affectedIds=[2]  {Choice_Value:309, Choice_Sentiment:1}
ObjectIdChanged    affectorId=319  affectedIds=[309]  orig_id:309, new_id:320
ZoneTransfer       affectorId=319  affectedIds=[320]  {zone_src:28, zone_dest:37, category:"Sacrifice"}
ResolutionComplete affectorId=319  affectedIds=[319]  {grpid:175894}
AbilityInstanceDeleted affectorId=301  affectedIds=[319]
```

### ChoiceResult semantics (Perforating Artist)

| Field | Value | Meaning |
|-------|-------|---------|
| `affectorId` | 301 | Perforating Artist (the permanent with the triggered ability) — NOT the trigger instance (319) |
| `affectedIds` | [2] | Seat id of the player who made the choice (the *opponent*, seat 2) |
| `Choice_Value` | 309 | instanceId of the chosen creature (sacrificed) |
| `Choice_Sentiment` | 1 | Consistent with all sacrifice/cost choices across sessions |

This answers the open question in the ChoiceResult wire spec (§8): **when the opponent makes the punisher choice, `affectedIds` points to the opponent seat**. The pattern is the same regardless of who triggers vs who chooses — `affectedIds` is always the seatId of the chooser.

### "Lose 3 life" path (triggers 307 and 329)

When the opponent chose to lose life, the SelectNreq is issued then:

**Trigger 307 SelectNreq `ids:[9, 11]`** — only 2 options (no valid sacrifice targets at that point).
**Trigger 329 SelectNreq `ids:[8, 9, 11]`** — all 3 options.

Resolution batch for "lose life" path (trigger 307, gsId 135):
```
SyntheticEvent       affectorId=307  affectedIds=[2]  {type:1}
ModifiedLife         affectorId=307  affectedIds=[2]  {life:-3}
ResolutionComplete   affectorId=307  affectedIds=[307]  {grpid:175894}
AbilityInstanceDeleted affectorId=301  affectedIds=[307]
```

Trigger 329 "lose life" (gsId 237) — same but **no SyntheticEvent**:
```
ModifiedLife         affectorId=329  affectedIds=[2]  {life:-3}
ResolutionComplete   affectorId=329  affectedIds=[329]  {grpid:175894}
AbilityInstanceDeleted affectorId=301  affectedIds=[329]
```

**No `ChoiceResult` annotation emitted for the "lose life" path** — only the sacrifice path emits ChoiceResult. The lose-life outcome is silent (just `ModifiedLife`).

**`SyntheticEvent` presence is inconsistent** — present in trigger 307, absent in trigger 329, same outcome (opponent loses 3 life). Likely a non-essential client-side cue.

### Trigger grpId

The trigger ability instance has grpId **175894** — this is the triggered ability's grpId, distinct from Perforating Artist's card grpId (93837). Used in `ResolutionStart`/`ResolutionComplete` `{grpid:175894}`.

### SelectN interaction pattern

Perforating Artist uses **two-level SelectN**:
1. First SelectNreq: opponent picks cost type (sac / discard / lose life) — `idType:"PromptParameterIndex"`
2. Second SelectNreq (only when sac or discard chosen): opponent picks which permanent/card — `idType:"InstanceId_ab2c"`

The `"lose life"` branch skips the second SelectNreq entirely.

---

## Part 3 — ChoiceResult spec update (extends existing spec)

The existing ChoiceResult spec (`docs/plans/2026-03-21-choiceresult-wire-spec.md`) covers sacrifice-outlet choices. This session adds the punisher variant:

| Scenario | affectorId | affectedIds | Choice_Value | ChoiceResult emitted? |
|----------|-----------|-------------|--------------|----------------------|
| Sac outlet (Vampiric Rites) | sac-outlet permanent | [chooser seat] | chosen instanceId | yes |
| Punisher sac (Perforating Artist) | trigger source permanent | [opponent seat] | chosen instanceId | yes |
| Punisher lose life | n/a | n/a | n/a | **no** |
| Punisher discard | expected: trigger source | expected: [opponent seat] | expected: card instanceId | unconfirmed (not seen in session) |

---

## Part 4 — Code status and gaps

### Raid (ETB conditional counter)

```
grep -rn -i "raid" matchdoor/src/main/kotlin/ → 0 results
```

Not needed — Forge handles the condition check. `CounterAdded`/`Counter` annotations are already implemented. **Needs puzzle to exercise.**

### Perforating Artist trigger flow

The trigger fires as a normal end-step triggered ability. Current gaps:

1. **SelectN for punisher choices** — `MatchSession.onSelectN()` handles `SelectNresp` but does not forward punisher choices to Forge. Forge needs to receive which cost the opponent chose. Unclear what Forge API supports for "force opponent to pay a cost". Flag for engine-bridge agent.

2. **ChoiceResult not emitted** — `choiceResult()` builder does not exist. Covered in existing ChoiceResult wire spec §7. For punisher, emit on the sacrifice branch only. Placement: first annotation in the resolution batch, before `ObjectIdChanged`/`ZoneTransfer`.

3. **SyntheticEvent (type=1)** — emitted inconsistently by Arena alongside life loss. Not implemented. Appears to be a client UI hint (e.g. "flash damage indicator"). Low priority.

4. **Two-level SelectN** — current `onSelectN` may handle one level. Punisher requires two sequential `SelectNreq/resp` pairs within the same resolution. The session confirms the second SelectNreq follows immediately after the first response, same gsId (gsId=185 for both req and first resp, gsId=186 for second req).

---

## Part 5 — Annotation type reference

New type encountered in this session:
- **ChoiceResult (58)** — punisher cost selection. Documented above. Zero implementation.

Types exercised by these cards:
- ZoneTransfer (1), ManaPaid (34), UserActionTaken (73), ResolutionStart (43), ResolutionComplete (44), AbilityInstanceCreated (36), AbilityInstanceDeleted (37), ObjectIdChanged (13), TappedUntappedPermanent (4), SyntheticEvent (72), ModifiedLife (10), ChoiceResult (58)

All except ChoiceResult are implemented.

---

## Cross-references

- `docs/plans/2026-03-21-choiceresult-wire-spec.md` — ChoiceResult full spec (answers open question in §8 about Perforating Artist)
- `docs/rosetta.md` Table 1, type 58 — missing `Choice_Sentiment` key (update: add it; note `Choice_Options` never observed)
- `docs/catalog.yaml` — no entry for raid, punisher triggers, or ChoiceResult mechanic — **update needed**
- `docs/annotation-field-notes.md` — add ChoiceResult / punisher observation
