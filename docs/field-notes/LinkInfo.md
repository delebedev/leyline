## LinkInfo — field note

**Status:** NOT IMPLEMENTED
**Instances:** 4 across 2 sessions (`2026-03-06_22-37-41`, `2026-03-07_11-49-05`)
**Proto type:** `AnnotationType.LinkInfo` (= 40)
**Field:** `persistentAnnotations`

### What it means in gameplay

Records the result of an "as-enters" choice that links a permanent to another object — specifically when a player chooses what basic land type, what creature type, or what card a permanent is associated with. The client uses this to display the linked relationship (e.g. "this dual land is currently a Forest", "this land fetched via sacrifice was found").

### Detail keys

| Key | Always/Sometimes | Values seen | Meaning |
|-----|-----------------|-------------|---------|
| `LinkType` | Always | 2, 3 | The kind of link. 2 = sacrifice/fetch relationship; 3 = "choose a type" relationship |
| `ChooseLinkType` | Sometimes | "Type" | Indicates the player made a type choice (vs. an object/card choice) |
| `sourceAbilityGRPID` | Sometimes | 193130 | The ability grpId that triggered the choice (e.g. Multiversal Passage's "choose a basic land type" ability) |

### Cards/objects observed

| Scenario | affectorId | affectedIds | Detail keys | Session |
|----------|-----------|------------|-------------|---------|
| Multiversal Passage (iid=282) enters BF — player chose "Forest" as land type | iid=282 (the land) | [6, 29] | `ChooseLinkType:"Type"`, `sourceAbilityGRPID:193130`, `LinkType:3` | `2026-03-06_22-37-41` gsId=25 |
| Escape Tunnel (iid=324, grp:100641) ability activated — sacrificed and fetched a land | ability iid=325 (ability grpId 88024) | [324] | `LinkType:2` | `2026-03-07_11-49-05` gsId=25 |

`affectedIds=[6, 29]` for the Multiversal Passage case: `6` is a player seat id, `29` is the chosen land type (Forest = 29? or instanceId of the forest choice). The `Choice_Value:29` in the concurrent `ChoiceResult` annotation confirms 29 is the chosen value — likely a land type enum value.

For Escape Tunnel: `affectedIds=[324]` = the land being sacrificed (the source of the triggered ability). The ability iid=325 (grpId=88024 = "{T}, Sacrifice CARDNAME: Search your library for a basic land card...") is the `affectorId`.

### Lifecycle

Persistent annotation. Created in the same GSM as the `ChoiceResult` annotation when a player makes an as-enters choice. For Multiversal Passage, it appears in the GSM immediately following the `ReplacementEffect` annotation (gsId=25 follows gsId=24).

For Escape Tunnel (fetch ability): appears in the GSM when the ability activates and the sacrifice is processed (same GSM as `ZoneTransfer` with `category=Sacrifice` on the land).

Deleted when:
- For "choose a type" (LinkType=3): persists as long as the permanent is on the battlefield.
- For "sacrifice/fetch" (LinkType=2): deleted after the fetch resolves (the link is transient — once the land is found, the relationship is complete).

### Related annotations

- `ChoiceResult` appears in the same GSM as LinkInfo for type-choice cases: `{Choice_Value:29, Choice_Domain:9, Choice_Sentiment:2}`. The `Choice_Domain:9` likely = "choose a land type" domain.
- `ReplacementEffect` appears one GSM earlier (gsId=24) for Multiversal Passage's entry replacement.
- `RemoveAbility`/`ModifiedType`/`LayeredEffect` trio tracks the ongoing effect of the choice.

### Our code status

- Builder: missing — no `linkInfo()` builder in `AnnotationBuilder.kt`
- GameEvent: missing — no Forge event for "player chose a type/link for an entering permanent"
- Emitted in pipeline: no

### Wiring assessment

**Difficulty:** Hard

Two distinct scenarios, each with a different hook:

1. **"Choose a type" replacement effect (LinkType=3):** fires during `confirmReplacementEffect` in `WebPlayerController`, same context as the `ReplacementEffect` annotation. After the player makes the type choice, emit LinkInfo with:
   - `affectorId` = the permanent instanceId
   - `affectedIds` = [seatId, chosenTypeValue]
   - `ChooseLinkType:"Type"`, `sourceAbilityGRPID` = the replacement ability grpId
   - `LinkType:3`

   The challenge is mapping Forge's type-choice result (an enum or string) to the correct `affectedIds` numeric value (29 = Forest type? Unclear).

2. **"Sacrifice/fetch" ability (LinkType=2):** fires when an Evolving Wilds / Escape Tunnel ability activates and the land is sacrificed. The `affectorId` = the activated ability instanceId, `affectedIds` = the sacrificed land. Hook would be in the activated ability resolution path, detecting sacrifice-then-search patterns.

The primary complexity for scenario 1 is understanding the `affectedIds` encoding: `[6, 29]` where 6 is a seat and 29 is unclear (land type enum? choice domain value?). More recordings with Multiversal Passage choosing different land types would clarify.

### Open questions

- What is `Choice_Domain:9` in the accompanying `ChoiceResult`? Is it "choose a basic land type" domain?
- What does `affectedIds=[6, 29]` mean exactly? Is 6 the player seat and 29 a numeric land type code?
- Are there other `LinkType` values beyond 2 and 3? Type 1 (choice-of-card?) not yet observed.
- Does LinkInfo appear for Companion linking, bestow choices, or other "link to an object" mechanics?
