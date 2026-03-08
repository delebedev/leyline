## ChoiceResult — field note

**Status:** NOT IMPLEMENTED
**Instances:** 5 across 2 sessions
**Proto type:** AnnotationType.ChoiceResult = 58
**Field:** annotations (transient)

### What it means in gameplay

Fires when a player completes a forced or prompted choice — selecting which permanent to sacrifice, which card to discard, which basic land type to name, etc. The client uses it for brief highlight/log feedback on the chosen object.

### Detail keys

| Key | Always/Sometimes | Values seen | Meaning |
|-----|-----------------|-------------|---------|
| Choice_Value | Always | 477, 492, 29 | instanceId (or low-range ID) of the chosen object |
| Choice_Sentiment | Always | 1, 2 | 1 = voluntary choice; 2 = unknown (possibly forced/random) |
| Choice_Domain | Sometimes (20%) | 9 | Category of the choice; value 9 observed with Multiversal Passage land type selection |

### Cards observed

| Card name | grpId | Scenario | Session |
|-----------|-------|----------|---------|
| Sheoldred's Edict | 83808 | Opponent sacrifices a creature; chose instance 477 | 2026-03-01_11-33-28 gsId=70 |
| Liliana of the Veil (-2) | 82149 | Target player sacrifices a creature; chose instance 492 | 2026-03-01_11-33-28 gsId=149 |
| Multiversal Passage | ~97998 | As-enters ETB choice: choose a basic land type (instance 29) | 2026-03-06_22-37-41 gsId=25 |

### Lifecycle

Transient annotation — appears in the `annotations` list (not `persistentAnnotations`). Sent in the same GSM as the consequence action (ZoneTransfer for sacrifice/discard, or the ETB resolution). No deletion step needed; transients are not tracked across GSMs.

The two seat copies of a GSM carry the same ChoiceResult (seats [1] and [2] both receive it).

### Related annotations

- `ObjectsSelected` — appears just before or alongside ChoiceResult when a player locks in a selection during simultaneous-choice effects (e.g. Liliana +1 discard). ChoiceResult is the outcome; ObjectsSelected is the confirmation of locking in.
- `ZoneTransfer` — always follows in the same GSM for sacrifice/discard outcomes.
- `LinkInfo` — observed alongside Multiversal Passage instance (links the chosen land type to the card).

### Our code status

- Builder: missing — no `ChoiceResult` method in AnnotationBuilder.kt
- GameEvent: missing — no dedicated Forge event for "choice finalized"
- Emitted in pipeline: no

### Wiring assessment

**Difficulty:** Medium

Intercept the moment a sacrifice/discard choice is finalized in Forge's input system (the `InteractivePromptBridge` response path). The chosen card is known at that point — emit a transient ChoiceResult annotation before the zone transfer.

For ETB modal choices (Multiversal Passage land type), the hook is harder — Forge's as-enters choices go through `SpellAbility` parameter handling, not a unified choice event. `Choice_Domain` may categorize the type of prompt (land type selection = 9) but more recordings needed to map the domain enum.

`Choice_Sentiment` semantics are unclear: value 1 appears for sacrifice and discard (both "forced" choices from the victim's perspective), value 2 appeared for an ETB self-choice (Multiversal Passage owner choosing). The "voluntary vs forced" framing may be backwards or the values encode something else entirely.

### Open questions

- Full `Choice_Domain` enum: what other values exist? Does 9 specifically mean "land type"?
- `Choice_Sentiment=2` on the Multiversal Passage instance — why different from the sacrifice instances? Is 2 "self-benefiting choice" vs 1 "targeted by opponent's effect"?
- Does `Choice_Value=29` refer to instanceId 29 (a land or basic land type token)? Low instanceIds in this session appear to be the initial objects — need to trace the opening full GSM.

---

*Migrated from `docs/annotation-field-notes.md`. New data vs old notes: old notes only had `Choice_Sentiment=[1]` and listed `Choice_Options` / `Choice_Domain` as "Sometimes 50%" — new variance report shows 5 instances (down from 8), `Choice_Domain` at 20%, `Choice_Sentiment=[1,2]`, no `Choice_Options`. The "Choice_Options" key from old notes may have been misread or is session-specific.*
