---
name: spec-prompt
description: Spec a GRE prompt type from real Arena recordings. Extracts wire shape, response contract, cross-game variance, and documents the full protocol for implementation. Use when implementing a new prompt handler or debugging an existing one.
---

## What I do

Research one GRE prompt type (e.g. OptionalActionMessage, SelectNReq, SearchReq) from saved Player.log games. Produces a structured prompt spec with wire shapes, response contracts, and implementation guidance.

## When to use me

- "spec OptionalAction" / "spec SelectNReq"
- Before implementing a new prompt handler in matchdoor
- When debugging prompt/response mismatches with the client
- As input to plan-fix for a prompt-related issue

## Inputs

- **Prompt type name** (required) — e.g. `OptionalAction`, `SelectN`, `Search`
  - Matches against `GREMessageType_<Name>*` in scry-ts

## Process

### 1. Find games with this prompt

```bash
just scry-ts game search <PromptType>
```

This searches across all saved games (raw log grep). Note which games have hits and how many.

### 2. Extract wire shapes

For each game with hits:

```bash
just scry-ts prompts --type <PromptType> --game <ref> --json
```

Collect the full JSON for every occurrence. Track:
- **GRE→Client fields** — which fields are always present, sometimes present, never present
- **Field value distributions** — do `promptId`, enum fields, array lengths vary?
- **`systemSeatIds`** — always one seat? Both? Pattern?
- **`allowCancel`** — what values appear?

### 3. Extract response shapes

Grep saved game logs for the client response type:

```bash
grep -A5 "ClientMessageType_<PromptType>Resp" ~/.scry/games/<ref>.log
```

Track:
- **Response field names** — `optionalResp`, `selectNResp`, etc.
- **Response enum values** — `OptionResponse_Allow_Yes`, `OptionResponse_Cancel_No`, etc.
- **`respId`** — should match prompt's `msgId`
- **`gameStateId`** — should match prompt's `gameStateId`

### 4. Check preceding GSM pattern

For each occurrence, inspect the GSM that precedes the prompt:

```bash
just scry-ts gsm show <gsId> --json | jq '.pendingMessageCount'
```

Document:
- `pendingMessageCount` value (usually 1 when a prompt follows)
- Phase/step when the prompt fires
- Whether the prompt arrives as a separate GRE message or bundled

### 5. Trace context per occurrence

For a representative sample (3-5 occurrences), resolve:
- **What card?** — trace `sourceId` or parameter CardIds to objects in the GSM
- **What ability?** — check ability annotations in same/preceding GSM
- **What happened after?** — diff GSMs before response and after to see outcome

```bash
# Card name from instanceId (check GSM objects)
just scry-ts gsm show <gsId> --json | jq '.gameObjects[] | select(.instanceId == <sourceId>)'

# Or use card manifest
just scry-ts game cards --game <ref>
```

### 6. Cross-game variance analysis

Compare across all games:
- Are wire shapes structurally identical or do fields appear/disappear?
- Do response patterns vary (always accept, mix of accept/decline)?
- Different `promptId` values → different loc strings → different UI prompts?
- Different `optionalActionTypes` or equivalent enum fields?

### 7. Check our code

```bash
# Proto definition
grep -A20 'message <PromptType>' matchdoor/src/main/proto/messages.proto

# Handler / builder
grep -rn '<PromptType>\|<promptType>' matchdoor/src/main/kotlin/

# Response dispatch
grep -rn '<PromptType>Resp\|<promptType>Resp' matchdoor/src/main/kotlin/

# GRE message type enum
grep '<PromptType>' matchdoor/src/main/proto/messages.proto
```

### 8. Write prompt spec

Output to `docs/prompt-specs/<PromptType>.md` using the template below.

## Output Template

```markdown
## <PromptType> — GRE prompt spec

**GRE type:** GREMessageType_<Name> = <number>
**Response type:** ClientMessageType_<Name>Resp
**Status:** NOT IMPLEMENTED / PARTIAL / OK
**Occurrences:** N across M games

### Wire shape (GRE → Client)

| Field | Always/Sometimes | Type | Example values |
|-------|-----------------|------|----------------|

### Wire shape (Client → GRE response)

| Field | Type | Example values |
|-------|------|----------------|

### Preceding GSM pattern

- `pendingMessageCount`: <value>
- Phase/step: <where this fires>
- Bundled or separate GRE message: <which>

### Gameplay meaning

<1-2 sentences: what the player sees, what decision they make>

### Occurrences (from recordings)

| Game | gsId | Card | Ability context | Response | Outcome |
|------|------|------|----------------|----------|---------|

### Our code status

- Proto: defined / missing
- Builder: exists / missing (file:line)
- Handler: exists / missing (file:line)
- Response dispatch: exists / missing (file:line)

### Dependencies

<Other prompt types, annotations, or GSM fields this interacts with>

### Implementation contract

- **Emit after:** <what game event or state triggers this prompt>
- **Detect via:** <Forge API call, event type, or game state check>
- **Response routing:** <how the client response gets back to the engine>
- **On Accept:** <what the engine does>
- **On Decline:** <what the engine does>

### Prompt IDs

| promptId | Loc string (if known) | UI text | Context |
|----------|-----------------------|---------|---------|

### Notes

<Edge cases, Forge API considerations, open questions>
```

## Key conventions

- **Recordings are truth.** Wire shapes come from `scry-ts prompts --json`, not guesses or proto comments.
- **Cross-game variance matters.** One game may only show the happy path. Check at least 3 games when available.
- **Response shapes matter as much as request shapes.** The response routing is where most bugs live.
- **Don't propose fixes.** This is research. The spec feeds into plan-fix or implementation.
- **`pendingMessageCount` is protocol-critical.** If the preceding GSM doesn't signal the pending prompt, the client won't wait for it.
- **`promptId` → loc key.** Different promptIds mean different client UI. Document which you've seen.
- **One prompt type at a time.** Don't batch — each has unique fields and semantics.

## Related skills

- **investigate-annotation** — same philosophy, for annotation types instead of prompts
- **recording-scope** — full mechanic scoping (covers prompts + annotations + diffs together)
- **card-spec** — per-card analysis that may reference prompt types as gaps
