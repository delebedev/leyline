# Error Taxonomy

Structured classification of failures for agent triage. Each category has a detection source, diagnostic path, and typical fix pattern.

## Categories

### 1. PROTOCOL — Wire format mismatch

**What:** Missing/wrong fields in GSM, incorrect enum values, malformed protobuf, invariant violations.

**Detection:**
- `InvariantChecker` violations (`gsid_monotonicity`, `annotation_ref`, `zone_object`, etc.)
- Client `DeserializationException` in Player.log (scry)
- `ValidatingMessageSink` failures in tests

**Diagnostic path:**
1. `just fd-response <CmdType>` — compare real server output to ours
2. `GET /api/recording-invariants?id=<session>` — check which invariants fail
3. Recording diff: our output vs golden

**Fix pattern:** StateMapper / BundleBuilder / proto builder fix. Always add conformance test.

**Examples:**
- Wrong `FormatType: "BotDraft"` (should be int enum)
- Missing `EventTags` field crashes client deserializer
- `gsId` self-referential (gsId == prevGsId)

---

### 2. ENGINE — Forge exception or wrong game behavior

**What:** Forge throws an exception, game rule applied incorrectly, wrong zone transfer, AI hangs.

**Detection:**
- Server logs `level=ERROR` via `GET /api/logs?level=ERROR`
- Game stuck (no phase advance) — `GET /api/priority-events`
- Wrong card in wrong zone — `GET /api/id-map`

**Diagnostic path:**
1. `tail -50 logs/leyline.log | grep -i exception`
2. `GET /api/priority-log` — check where priority got stuck
3. Compare engine state vs protocol state via `GET /api/state-diff`

**Fix pattern:** Bridge adapter fix, game event handler, AI behavior. Integration test.

**Examples:**
- `NullPointerException` in `GameBridge.getZoneCards()`
- Combat damage not applied (attacker list empty due to `.ifEmpty{}` bug)
- AI turn hangs (missing `awaitPriority()` before sending prompt)

---

### 3. CLIENT — Player.log exception after receiving our message

**What:** Client parsed our message but crashed rendering/processing it.

**Detection:**
- `scry state` — annotation parse exceptions, bare exceptions
- `AnnotationParseException` with annotation type ID in Player.log

**Diagnostic path:**
1. Check annotation type ID against `docs/rosetta.md`
2. Compare our annotation vs real server annotation for same type
3. `GET /api/game-states` — find the GSM that contained the bad annotation

**Fix pattern:** Annotation builder fix. Often a missing detail key or wrong value type.

**Examples:**
- `FormatException` parsing `AddAbility` annotation (wrong KVP format)
- `AnnotationParseException` for type `[60]` (LayeredEffect missing affectedIds)

---

### 4. VISUAL — Screen doesn't match expected state

**What:** OCR can't find expected text, screen looks wrong, stuck on unexpected screen.

**Detection:**
- `arena where` returns unexpected screen or `null`
- `arena wait` times out
- `arena issues` shows repeated click failures

**Diagnostic path:**
1. `arena ocr` — what text is actually on screen?
2. `arena scene` — what does Player.log think the screen is?
3. `arena capture --out /tmp/debug.png` — screenshot for visual inspection

**Fix pattern:** Navigation fix (wrong coords, wrong OCR anchor), or server-side fix if the screen content is wrong (FD handler returning bad data).

**Examples:**
- "Play" button not found (deck not selected, button not rendered)
- Ghost course from mode switch (stale `Resume` button)
- Event title missing (bad `titleLocKey`)

---

### 5. BUILD — Stale bytecode, missing class, compilation error

**What:** Server crashes on startup or uses old code despite source changes.

**Detection:**
- `NoClassDefFoundError` or `ClassNotFoundException` in logs
- Behavior doesn't match source code
- `just serve` fails to start

**Diagnostic path:**
1. `just serve` now auto-rebuilds (should catch most issues)
2. If still broken: `./gradlew clean classes`
3. Check for new `.kt` files that need `--rerun-tasks`

**Fix pattern:** Rebuild. `just serve` handles this automatically now.

---

### 6. TIMEOUT — Action didn't produce expected state change

**What:** Clicked a button, dragged a card, or sent a command — but the expected follow-up state never arrived.

**Detection:**
- `arena wait` exits with timeout
- `arena play` drag verification fails after 3 retries
- `arena navigate` stuck on a transition

**Diagnostic path:**
1. Check if the action actually fired: `arena issues` (was click registered?)
2. Check server state: `GET /api/state` (did the engine process it?)
3. Check client state: `arena ocr` (is the client showing something unexpected?)
4. Check for errors: `just scry state` + `tail logs/leyline.log`

**Fix pattern:** Depends on root cause:
- Action not registered → retry with different coords or `--retry`
- Server processed but client didn't update → protocol/client issue
- Server didn't process → engine/priority issue

---

### 7. NAVIGATION — Wrong screen, can't find path

**What:** Agent is on a screen it doesn't recognize, or can't find a path to the target.

**Detection:**
- `arena where` returns `null`
- `arena navigate` says "No path from X to Y"
- Agent in a modal/popup not in the state machine

**Diagnostic path:**
1. `arena ocr` — read all text on screen
2. `arena scene` — check Player.log scene
3. Is this a new screen not in the state machine? Add it.

**Fix pattern:** Add screen + transitions to `screens_data.py` (see `tools/arena/README.md` § Adding a Screen).

---

## Triage Order

When something breaks, classify first:

```
1. Is there an exception in Player.log?     → CLIENT
2. Is there an exception in server logs?    → ENGINE  
3. Did InvariantChecker flag it?            → PROTOCOL
4. Did `just serve` fail to start?          → BUILD
5. Did an arena action time out?            → TIMEOUT
6. Is the screen unrecognized?              → NAVIGATION
7. Is the screen wrong (unexpected content)?→ VISUAL
```

## Error Sources → Category Mapping

| Source | Detector | Categories |
|---|---|---|
| Player.log exceptions | `scry state` | CLIENT, PROTOCOL |
| Server logs | `GET /api/logs?level=ERROR` | ENGINE, BUILD |
| InvariantChecker | `ValidatingMessageSink` / `/api/recording-invariants` | PROTOCOL |
| Arena CLI failures | `arena issues` | TIMEOUT, VISUAL, NAVIGATION |
| Session analysis | `/api/recording-analysis` | PROTOCOL, ENGINE |
| Build output | `just serve` / `just build` | BUILD |
