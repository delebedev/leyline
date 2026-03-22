# ChoiceResult Wire Spec

**Session:** `recordings/2026-03-21_22-31-46-game3`
**Proto type:** `AnnotationType.ChoiceResult = 58`
**Status:** NOT IMPLEMENTED — no builder, no Forge hook, not emitted

---

## 1. Instances found

This recording has **2 ChoiceResult annotations** — both are seat copies of the same single event (gsId=309, annotation id=654). One prior field note (`docs/field-notes/ChoiceResult.md`) documented 5 instances from 2 earlier sessions. Combined corpus: 3 distinct events across 3 sessions.

---

## 2. Full annotation JSON (this session)

```json
{
  "id": 654,
  "types": ["ChoiceResult"],
  "affectorId": 354,
  "affectedIds": [1],
  "details": {
    "Choice_Value": 353,
    "Choice_Sentiment": 1
  }
}
```

Identical in both seat copies (systemSeatIds=[2] and [1]).

### Field breakdown

| Field | Value | Meaning |
|-------|-------|---------|
| `affectorId` | 354 | instanceId of Vampiric Rites (grpId 93787 = Vampire Gourmand — **wrong, see §6**) — the permanent whose activated ability triggered the sacrifice prompt |
| `affectedIds` | [1] | Player seat id 1 (the chooser — the controller of Vampiric Rites who selected which creature to sacrifice) |
| `Choice_Value` | 353 | instanceId of Reassembling Skeleton — the creature chosen to sacrifice |
| `Choice_Sentiment` | 1 | "1" observed on all sacrifice choices across all sessions; likely means "cost/mandatory" or "targeted by ability" |

**Detail keys present:** `Choice_Value`, `Choice_Sentiment`
**Detail keys absent:** `Choice_Domain` (not present — consistent with prior notes saying it appears only ~20% of instances, specifically for ETB land-type choices)

---

## 3. Message context (3 messages before/after)

### Sequence (gsId order)

```
gsId=306  msgId=398  GameStateMessage Diff  [seat 2 then seat 1]
  annotations: TappedUntappedPermanent x3, AbilityInstanceCreated
  objects: instance 333 (Cackling Prowler, BF), 353 (Reassembling Skeleton, BF),
           354 (Vampire Gourmand, BF), 365 (grpId 175832 ability, Stack)
  → Vampiric Rites ability goes on stack; 3 lands tapped for mana

gsId=307  msgId=399  GameStateMessage Diff  [both seats]
  annotations: (none)
  → Priority pass / empty diff

gsId=308  msgId=400  GameStateMessage Diff  [both seats]
  annotations: ResolutionStart (affectorId=ability instance)
  → Resolution of the Vampiric Rites activated ability begins

gsId=308  msgId=401  SelectNreq  [seat 1 only]
  promptType: SelectNReq
  promptData: {
    minSel:1, maxSel:1,
    context:"Resolution_a163", optionContext:"Resolution_a9d7",
    listType:"Dynamic",
    ids:[333, 353],          ← options: Cackling Prowler, Reassembling Skeleton
    sourceId: 354,           ← Vampire Gourmand (the sac outlet)
    idType:"InstanceId_ab2c"
  }
  → Server presents sacrifice selection to seat 1

[UIMessage x5]  ← client UI interaction frames (no GRE annotations)

gsId=308  SelectNresp  [C→S]
  → Seat 1 submits selection (chose instance 353 = Reassembling Skeleton)

▼▼▼ ChoiceResult fires here ▼▼▼

gsId=309  msgId=402  GameStateMessage Diff  [seat 2]  ← line 911
  annotations:
    [0] ChoiceResult   affectorId=354  affectedIds=[1]  {Choice_Value:353, Choice_Sentiment:1}
    [1] ObjectIdChanged  affectorId=365  353→366
    [2] ZoneTransfer   affectorId=365  affectedIds=[366]  {zone_src:28, zone_dest:33, category:"Sacrifice"}
    [3] ObjectIdChanged  affectorId=365  173→367
    [4] ZoneTransfer   affectorId=365  affectedIds=[367]  {zone_src:32, zone_dest:31, category:"Draw"}
    [5] ResolutionComplete  affectorId=365  {grpid:175832}
    [6] AbilityInstanceDeleted  affectorId=354  affectedIds=[365]
  diffDeletedInstanceIds: [365]
  → Sacrifice resolves: skeleton moves BF→GY, draw resolves, ability instance deleted

gsId=309  msgId=402  GameStateMessage Diff  [seat 1]  ← line 912
  (identical annotations, plus object 367 grpId=93818 in hand visible to seat 1)

gsId=309  msgId=403  ActionsAvailableReq  [seat 1]
  → Priority restored to seat 1 post-resolution
```

### Position in annotation list

ChoiceResult is annotation [0] — first in the list, before any ZoneTransfer consequences. This is consistent across all prior instances: ChoiceResult leads the annotation batch.

---

## 4. MultistepEffectStarted/Complete pairing

**Not paired** with this ChoiceResult. MultistepEffect (SubCategory=15) appears only in gsId=9/10 — the very beginning of the match, unrelated context (likely opening hand decision).

The ChoiceResult here stands alone: no MultistepEffectStarted bracket before it, no MultistepEffectComplete after it. This is consistent with "simple" sacrifice choices. MultistepEffect may only bracket multi-step ability chains (e.g. modal effects with several separate prompts), not single-choice sacrifices.

---

## 5. Code status

```
grep -rn "ChoiceResult" matchdoor/src/main/kotlin/ → 0 results
```

No `choiceResult()` method in `AnnotationBuilder.kt`. Not wired anywhere in the pipeline.

---

## 6. Card identity disambiguation (this session)

| instanceId | grpId | Card name | Role |
|-----------|-------|-----------|------|
| 354 | 93787 | Vampire Gourmand | affectorId — the battlefield permanent with the activated sac ability |
| 353 | 93895 | Reassembling Skeleton | `Choice_Value` — chosen sacrifice target |
| 333 | 93814 | Cackling Prowler | the unchosen option in the SelectN |
| 365 | 175832 | (ability instance on stack) | affectorId in ZoneTransfer/ResolutionComplete |

Note: The task description mentions "Perforating Artist" (grpId 93837) but that card does not appear in this session's notes.md or in the recording. The punisher-style choice mechanic here is **Vampire Gourmand's activated ability** channeled through **Vampiric Rites** (94043) — a "sac a creature: gain 1 life, draw a card" outlet. The sacrifice choice structure is functionally equivalent to a punisher choice: one player picks which of their creatures dies.

Perforating Artist observations, if they exist, would be from a different session. This spec covers the Vampiric Rites sac-outlet pattern.

---

## 7. Wire spec — how to emit ChoiceResult

### Trigger point

After a `SelectNresp` is received and the server resolves the selection, before emitting the consequence ZoneTransfer. Specifically: intercept in `MatchSession.onSelectN()` at the point where the selected instanceId is confirmed and handed to Forge.

### Fields to populate

```
affectorId   = instanceId of the permanent / source driving the choice
               (for sac choices: the sac-outlet permanent, e.g. Vampire Gourmand)
               NOT the ability instance id on the stack
affectedIds  = [seatId of the player who made the choice]
               (always a single player seat id, not an instance id)
Choice_Value = instanceId of the chosen object (the selected card/permanent)
Choice_Sentiment = 1  (always 1 for sacrifice/discard; use 2 only for ETB self-choices
                        — see field note for Multiversal Passage data point)
```

`Choice_Domain` is omitted for sacrifice/discard choices. Only emit it for ETB modal choices — value 9 seen for land-type selection (Multiversal Passage).

### Placement in annotation list

Emit ChoiceResult as annotation [0] — before ObjectIdChanged and ZoneTransfer in the same GSM batch.

### Lifecycle

Transient only — goes in `annotations`, not `persistentAnnotations`. No deletion step.

### Forge hook candidates

- `MatchSession.onSelectN()` — receives the `SelectNresp`, calls Forge with the selection. The chosen instanceId is available here.
- `InteractivePromptBridge` response path — alternative if onSelectN doesn't have the sourceId (permanent affector) easily accessible.

The tricky part: ChoiceResult's `affectorId` is the *permanent* (354 = Vampire Gourmand), not the *ability instance* on the stack (365). The ability instance id is used in ZoneTransfer/ResolutionComplete. Need to look up the source permanent from the stack ability at the time of resolution. The SelectNreq already carries `sourceId: 354` — that value should be stored in the session state when the prompt was issued and retrieved when the response arrives.

---

## 8. Open questions (updated)

- `Choice_Sentiment=2` still unexplained — only seen in Multiversal Passage self-benefit ETB choice. Is 2 "I chose this for myself" vs 1 "I was forced/targeted"? Or is it indexed to a different enum entirely?
- `Choice_Domain=9` — only seen once (Multiversal Passage, land type selection). What other domains exist? Does domain=9 specifically mean "basic land type prompt"?
- ~~Does Perforating Artist (punisher: opponent picks sac/discard/lose life) send ChoiceResult for the *opponent* making the "punish" selection?~~ **RESOLVED** — `recordings/2026-03-21_22-31-46` contains 3 Perforating Artist trigger instances. ChoiceResult fires **only for the sacrifice branch**, not for "lose 3 life". `affectedIds=[opponent_seat]` — confirmed. See `docs/plans/2026-03-21-raid-wire-spec.md` §3.
- `Choice_Value=29` from the Multiversal Passage instance (prior sessions) — instanceId 29 is very low; likely refers to a zone or a synthetic object, not a card. What is instanceId 29 in that session's opening full GSM?

---

## 9. Cross-reference

- Field note (prior state): `docs/field-notes/ChoiceResult.md`
- Rosetta row: Table 1, type 58 — detail keys listed as `Choice_Value, Choice_Options, Choice_Domain`. `Choice_Options` not seen in any instance; `Choice_Sentiment` is missing from the Rosetta entry — **update Rosetta**.
- Related annotation: `ObjectsSelected` (type 31) — not seen in this session alongside ChoiceResult. Prior field note says it appears "just before or alongside" for simultaneous-choice effects (Liliana +1 discard). Not present here for single-target sacrifice.
