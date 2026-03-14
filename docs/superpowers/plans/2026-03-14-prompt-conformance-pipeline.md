# Prompt Conformance Pipeline Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extend the conformance pipeline to support lossless prompt message decoding and lifecycle-based diffing for DeclareAttackers, DeclareBlockers, and GroupReq.

**Architecture:** Add `promptData` (full proto JSON via `JsonFormat.printer()`) to `RecordingDecoder.DecodedMessage`. Extend `segments.py` with prompt lifecycle discovery, extraction, templatization, and diffing. Add engine-side prompt serialization and conformance test scenarios.

**Tech Stack:** protobuf-java-util (`JsonFormat.printer()`), Python (segments.py), Kotest (engine tests), just (recipes)

**Spec:** `docs/superpowers/specs/2026-03-14-prompt-conformance-pipeline-design.md`

---

## Task 1: Add protobuf-java-util dependency

**Files:**
- Modify: `tooling/build.gradle.kts`
- Modify: `matchdoor/build.gradle.kts`

- [ ] **Step 1: Add dependency to tooling module**

In `tooling/build.gradle.kts`, add to the `dependencies` block:

```kotlin
implementation(libs.protobuf.java.util)
```

- [ ] **Step 2: Add dependency to matchdoor module**

In `matchdoor/build.gradle.kts`, find the `dependencies` block and add:

```kotlin
implementation(libs.protobuf.java.util)
```

- [ ] **Step 3: Verify build**

Run: `just build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add tooling/build.gradle.kts matchdoor/build.gradle.kts
git commit -m "chore: add protobuf-java-util dependency for lossless prompt serialization"
```

---

## Task 2: Add lossless promptData to RecordingDecoder

**Files:**
- Modify: `tooling/src/main/kotlin/leyline/recording/RecordingDecoder.kt`

The key change: for prompt messages (DeclareAttackersReq/Resp, DeclareBlockersReq/Resp, SubmitAttackers/Blockers, GroupReq/Resp), serialize the inner proto message via `JsonFormat.printer()` and embed as `promptData` in `DecodedMessage`. Existing summary fields remain for backward compat.

- [ ] **Step 1: Add import**

Add to imports at top of RecordingDecoder.kt:

```kotlin
import com.google.protobuf.util.JsonFormat
import kotlinx.serialization.json.JsonNull
```

- [ ] **Step 2: Add fields to DecodedMessage**

Add two fields to `DecodedMessage` data class, after the `clientGroupResp` field (line ~144):

```kotlin
    // Lossless prompt data — full proto JSON via JsonFormat.printer()
    // Set for prompt messages (DeclareAttackers/Blockers Req/Resp, Submit*, GroupReq/Resp)
    val promptType: String? = null,
    val promptData: JsonElement? = null,
```

- [ ] **Step 3: Add helper to serialize proto to JsonElement**

Add a private helper method in the `RecordingDecoder` object, after the existing `summarize*` methods:

```kotlin
private val jsonPrinter = JsonFormat.printer()
    .omittingInsignificantWhitespace()
    .preservingProtoFieldNames()

private fun protoToJsonElement(msg: com.google.protobuf.Message): JsonElement {
    val jsonStr = jsonPrinter.print(msg)
    return Json.parseToJsonElement(jsonStr)
}
```

Note: `preservingProtoFieldNames()` keeps snake_case field names from the proto definition — consistent with what `protoc --decode` shows.

- [ ] **Step 4: Wire promptData into decodeGRE for server→client messages**

In `decodeGRE()` (line ~572), add prompt data extraction. After line 621 (`promptId = ...`), before `systemSeatIds`:

```kotlin
promptType = when {
    gre.hasDeclareAttackersReq() -> "DeclareAttackersReq"
    gre.hasDeclareBlockersReq() -> "DeclareBlockersReq"
    gre.hasGroupReq() -> "GroupReq"
    gre.hasSelectTargetsReq() -> "SelectTargetsReq"
    else -> null
},
promptData = when {
    gre.hasDeclareAttackersReq() -> protoToJsonElement(gre.declareAttackersReq)
    gre.hasDeclareBlockersReq() -> protoToJsonElement(gre.declareBlockersReq)
    gre.hasGroupReq() -> protoToJsonElement(gre.groupReq)
    gre.hasSelectTargetsReq() -> protoToJsonElement(gre.selectTargetsReq)
    else -> null
},
```

- [ ] **Step 5: Wire promptData into decodeClientGRE for client→server messages**

Find the `decodeClientGRE()` method. Add `promptType` and `promptData` fields to the `DecodedMessage` constructor call for client messages:

```kotlin
promptType = when {
    client.hasDeclareAttackersResp() -> "DeclareAttackersResp"
    client.hasSubmitAttackersReq() -> "SubmitAttackersReq"
    client.hasDeclareBlockersResp() -> "DeclareBlockersResp"
    client.hasSubmitBlockersReq() -> "SubmitBlockersReq"
    client.hasGroupResp() -> "GroupResp"
    else -> null
},
promptData = when {
    client.hasDeclareAttackersResp() -> protoToJsonElement(client.declareAttackersResp)
    client.hasSubmitAttackersReq() -> {
        // SubmitAttackersReq is type-only (no payload). Emit empty JSON object.
        buildJsonObject {}
    }
    client.hasDeclareBlockersResp() -> protoToJsonElement(client.declareBlockersResp)
    client.hasSubmitBlockersReq() -> buildJsonObject {}
    client.hasGroupResp() -> protoToJsonElement(client.groupResp)
    else -> null
},
```

- [ ] **Step 6: Build and verify**

Run: `just build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Re-decode a recording to verify promptData appears**

```bash
# Re-decode the combat recording
just tape proto decode-session 2026-03-06_22-37-41
# Then check the output
python3 -c "
import json
with open('recordings/2026-03-06_22-37-41/md-frames.jsonl') as f:
    for line in f:
        msg = json.loads(line)
        if msg.get('promptType') == 'DeclareAttackersReq':
            print(json.dumps(msg, indent=2)[:2000])
            break
"
```

Expected: `promptData` field contains full proto JSON with `attackerInstanceId`, `legalDamageRecipients`, `selectedDamageRecipient`, `canSubmitAttackers`, `manaCost`.

- [ ] **Step 8: Commit**

```bash
git add tooling/src/main/kotlin/leyline/recording/RecordingDecoder.kt
git commit -m "feat(recording): lossless promptData for prompt messages via JsonFormat

DeclareAttackers/Blockers Req/Resp, Submit*, GroupReq/Resp now include
full proto JSON in promptData field. Existing summary fields retained
for backward compat.

Fixes the lossy decoder root cause — no more silently dropped fields."
```

---

## Task 3: Add prompt lifecycle discovery to segments.py

**Files:**
- Modify: `tools/tape/segments.py`

Add prompt lifecycle discovery and extraction alongside existing zone-transfer segment mining.

- [ ] **Step 1: Add prompt type constants and lifecycle datastructures**

Add after the existing `_SKIP_KEYS` frozenset (~line 270):

```python
# --- Prompt Lifecycle ---

PROMPT_REQUEST_TYPES = {
    'DeclareAttackersReq': 'DeclareAttackers',
    'DeclareBlockersReq': 'DeclareBlockers',
    'GroupReq': 'Group',
}

PROMPT_SUBMIT_TYPES = {
    'DeclareAttackers': 'SubmitAttackersReq',
    'DeclareBlockers': 'SubmitBlockersReq',
}

PROMPT_RESPONSE_TYPES = {
    'DeclareAttackers': 'DeclareAttackersResp',
    'DeclareBlockers': 'DeclareBlockersResp',
    'Group': 'GroupResp',
}


def find_prompt_lifecycles(frames):
    """
    Discover prompt lifecycles from frames.

    Scans for promptType fields (set by RecordingDecoder for prompt messages).
    Groups each initial request with its echo rounds and final submit.

    Returns dict: {category: [PromptLifecycle, ...]}
    """
    lifecycles = {}

    i = 0
    while i < len(frames):
        frame = frames[i]
        prompt_type = frame.get('promptType')

        if prompt_type not in PROMPT_REQUEST_TYPES:
            i += 1
            continue

        category = PROMPT_REQUEST_TYPES[prompt_type]
        lifecycle = extract_prompt_lifecycle(frames, i, category)
        lifecycles.setdefault(category, []).append(lifecycle)

        # Skip past the lifecycle
        i = lifecycle['end_index'] + 1

    return lifecycles


def extract_prompt_lifecycle(frames, start_idx, category):
    """
    Extract a complete prompt lifecycle starting at start_idx.

    Returns:
    {
        'category': str,
        'start_index': int,
        'end_index': int,
        'initial_req': dict,        # full frame with promptData
        'initial_gsms': [dict],     # GSMs accompanying initial req
        'rounds': [                 # 0-N echo rounds
            {
                'response': dict,   # client response frame
                'echo_gsms': [dict],# server echo GSM diffs
                'echo_req': dict,   # server re-prompt (or None)
            }
        ],
        'submit': dict | None,      # SubmitAttackersReq/SubmitBlockersReq
        'submit_resp': dict | None,
        'result_gsm': dict | None,  # post-submit GSM
    }
    """
    resp_type = PROMPT_RESPONSE_TYPES.get(category)
    submit_type = PROMPT_SUBMIT_TYPES.get(category)

    lifecycle = {
        'category': category,
        'start_index': start_idx,
        'end_index': start_idx,
        'initial_req': frames[start_idx],
        'initial_gsms': [],
        'rounds': [],
        'submit': None,
        'submit_resp': None,
        'result_gsm': None,
    }

    # Collect GSMs that immediately preceded the initial req (same gsId batch)
    req_gs = frames[start_idx].get('gsId', 0)
    j = start_idx - 1
    while j >= 0:
        f = frames[j]
        if f.get('gsId') == req_gs and f.get('gsmType') in ('Diff', 'Full'):
            lifecycle['initial_gsms'].insert(0, f)
            j -= 1
        else:
            break

    # Walk forward collecting rounds
    i = start_idx + 1
    current_round = None

    while i < len(frames):
        f = frames[i]
        pt = f.get('promptType') or f.get('greType', '')

        # Client response (DeclareAttackersResp, GroupResp, etc.)
        if f.get('promptType') == resp_type:
            if current_round:
                lifecycle['rounds'].append(current_round)
            current_round = {
                'response': f,
                'echo_gsms': [],
                'echo_req': None,
            }
            lifecycle['end_index'] = i
            i += 1
            continue

        # Submit (SubmitAttackersReq, etc.) — ends lifecycle
        if submit_type and f.get('promptType') == submit_type:
            if current_round:
                lifecycle['rounds'].append(current_round)
                current_round = None
            lifecycle['submit'] = f
            lifecycle['end_index'] = i
            # Look for submit response and result GSM
            if i + 1 < len(frames):
                nxt = frames[i + 1]
                if nxt.get('gsmType') in ('Diff', 'Full'):
                    lifecycle['result_gsm'] = nxt
                    lifecycle['end_index'] = i + 1
            break

        # GSM diff — part of echo or result
        if f.get('gsmType') in ('Diff', 'Full'):
            if current_round and current_round['echo_req'] is None:
                current_round['echo_gsms'].append(f)
                lifecycle['end_index'] = i
                i += 1
                continue
            # GSM after echo_req but before next response — still part of round
            if current_round:
                current_round['echo_gsms'].append(f)
                lifecycle['end_index'] = i
                i += 1
                continue

        # Server re-prompt (same type as initial) — echo req
        if f.get('promptType') == PROMPT_REQUEST_TYPES.get(f.get('promptType', ''), '___'):
            # This check is wrong — we need to match the REQUEST type, not the response
            pass

        # Re-prompt of same type
        req_type_name = None
        for rt, cat in PROMPT_REQUEST_TYPES.items():
            if cat == category:
                req_type_name = rt
                break
        if f.get('promptType') == req_type_name:
            if current_round:
                current_round['echo_req'] = f
                lifecycle['end_index'] = i
            i += 1
            continue

        # GroupReq lifecycle: ends after GroupResp (no submit)
        if category == 'Group' and current_round:
            # GroupResp already captured as response, look for result GSM
            lifecycle['rounds'].append(current_round)
            current_round = None
            if f.get('gsmType') in ('Diff', 'Full'):
                lifecycle['result_gsm'] = f
                lifecycle['end_index'] = i
            break

        # Phase transition or different prompt type — lifecycle boundary
        if f.get('promptType') and f.get('promptType') not in (resp_type, submit_type, req_type_name):
            break

        # Unknown frame in lifecycle — skip
        lifecycle['end_index'] = i
        i += 1

    # Flush any pending round
    if current_round:
        lifecycle['rounds'].append(current_round)

    return lifecycle
```

- [ ] **Step 2: Extend list_all_categories to include prompts**

Modify the `cmd_list` function to also show prompt lifecycles:

```python
def cmd_list(args):
    """List all segment categories in a recording."""
    session = args[0] if args else None
    path = find_md_jsonl(session)
    frames = load_frames(path)

    # Zone-transfer categories (existing)
    cats = list_all_categories(frames)
    print(f"Session: {session_name(path)}")
    print(f"Frames: {len(frames)}")
    print()
    for cat, indices in sorted(cats.items()):
        print(f"  {cat}: {len(indices)} segments at indices {indices}")

    # Prompt lifecycles (new)
    prompt_cats = find_prompt_lifecycles(frames)
    if prompt_cats:
        print()
        for cat, lifecycles in sorted(prompt_cats.items()):
            indices = [lc['start_index'] for lc in lifecycles]
            round_counts = [len(lc['rounds']) for lc in lifecycles]
            print(f"  {cat}: {len(lifecycles)} lifecycles at indices {indices} (rounds: {round_counts})")
```

- [ ] **Step 3: Add cmd_show for prompt lifecycles**

Add a new command to show a specific prompt lifecycle with full promptData:

```python
def cmd_show(args):
    """Show a specific prompt lifecycle with full proto fields."""
    if not args:
        print("Usage: segments.py show <category> [session] [--index N]", file=sys.stderr)
        sys.exit(1)
    category = args[0]
    session = None
    target_index = 0

    i = 1
    while i < len(args):
        if args[i] == "--index" and i + 1 < len(args):
            target_index = int(args[i + 1])
            i += 2
        else:
            session = args[i]
            i += 1

    path = find_md_jsonl(session)
    frames = load_frames(path)
    prompt_cats = find_prompt_lifecycles(frames)

    if category not in prompt_cats:
        print(f"No {category} lifecycles found. Available: {list(prompt_cats.keys())}", file=sys.stderr)
        sys.exit(1)

    lifecycles = prompt_cats[category]
    if target_index >= len(lifecycles):
        print(f"Only {len(lifecycles)} lifecycles, index {target_index} out of range", file=sys.stderr)
        sys.exit(1)

    lc = lifecycles[target_index]
    print(f"# Session: {session_name(path)}")
    print(f"# Category: {category}, index: {target_index}")
    print(f"# Frames: {lc['start_index']}..{lc['end_index']}")
    print(f"# Rounds: {len(lc['rounds'])}")
    print()

    # Show initial request promptData
    print("=== Initial Request ===")
    print(json.dumps(lc['initial_req'].get('promptData'), indent=2))
    print()

    # Show each round
    for ri, rnd in enumerate(lc['rounds']):
        print(f"=== Round {ri} ===")
        print(f"--- Response (promptData) ---")
        print(json.dumps(rnd['response'].get('promptData'), indent=2))
        if rnd['echo_req']:
            print(f"--- Echo Request (promptData) ---")
            print(json.dumps(rnd['echo_req'].get('promptData'), indent=2))
        print()

    if lc['submit']:
        print("=== Submit ===")
        print(json.dumps(lc['submit'].get('promptData'), indent=2))
```

- [ ] **Step 4: Wire cmd_show into main dispatch**

In the `main()` function at the bottom of segments.py, add `"show"` to the command dispatch:

```python
commands = {
    "list": cmd_list,
    "extract": cmd_extract,
    "template": cmd_template,
    "puzzle": cmd_puzzle,
    "diff": cmd_diff,
    "show": cmd_show,       # NEW
}
```

- [ ] **Step 5: Test manually against recording**

```bash
# Re-decode first (if not done in Task 2)
just tape proto decode-session 2026-03-06_22-37-41

# List — should show DeclareAttackers, DeclareBlockers, Group
just tape segment list 2026-03-06_22-37-41

# Show first DeclareAttackers lifecycle — should show legalDamageRecipients
just tape segment show DeclareAttackers 2026-03-06_22-37-41
```

Expected: `tape segment list` shows DeclareAttackers/DeclareBlockers/Group counts. `tape segment show` displays full proto JSON including `legalDamageRecipients`, `selectedDamageRecipient`, `canSubmitAttackers`.

- [ ] **Step 6: Commit**

```bash
git add tools/tape/segments.py
git commit -m "feat(conformance): prompt lifecycle discovery and show command

segments.py now discovers DeclareAttackers, DeclareBlockers, and Group
prompt lifecycles from promptType fields in md-frames.jsonl. Each
lifecycle captures: initial req, echo rounds, submit, result GSM.

New command: tape segment show <category> — displays full proto JSON
for each phase of a prompt lifecycle."
```

---

## Task 4: Add prompt templatization and diffing

**Files:**
- Modify: `tools/tape/segments.py`

Extend the template and diff commands to handle prompt lifecycles.

- [ ] **Step 1: Add prompt templatization**

Add after the `cmd_show` function:

```python
# Keys in promptData that should never be templatized (structural, not instance IDs)
_PROMPT_SKIP_KEYS = frozenset({
    'playerSystemSeatId', 'player_system_seat_id',
    'type',                    # DamageRecipient type
    'promptId', 'prompt_id',
    'canSubmitAttackers', 'can_submit_attackers',
    'hasRequirements', 'has_requirements',
    'hasRestrictions', 'has_restrictions',
    'mustAttack', 'must_attack',
    'maxAttackers', 'max_attackers',
    'isFacedown', 'is_facedown',
    'alternativeGrpId', 'alternative_grp_id',
    'grpId', 'grp_id',
    'zoneType', 'zone_type',
    'subZoneType', 'sub_zone_type',
    'lowerBound', 'lower_bound',
    'upperBound', 'upper_bound',
    'groupType', 'group_type',
    'context',
    'count', 'color',
})


def collect_prompt_instance_ids(lifecycle):
    """Collect all instance IDs from a prompt lifecycle's promptData fields."""
    ids = set()

    def _scan(obj, key=None):
        if isinstance(obj, dict):
            for k, v in obj.items():
                _scan(v, k)
        elif isinstance(obj, list):
            for item in obj:
                _scan(item, key)
        elif isinstance(obj, int) and obj > 10 and key not in _PROMPT_SKIP_KEYS:
            # Instance IDs are > 10 (seat IDs are 1-2, zone IDs are 18-38)
            # Additional heuristic: values > 40 are almost certainly instance IDs
            if obj > 40:
                ids.add(obj)

    # Scan all promptData in the lifecycle
    for field in ('initial_req',):
        _scan(lifecycle[field].get('promptData'))

    for rnd in lifecycle.get('rounds', []):
        _scan(rnd['response'].get('promptData'))
        if rnd.get('echo_req'):
            _scan(rnd['echo_req'].get('promptData'))

    # Also scan echo GSM objects/annotations
    for rnd in lifecycle.get('rounds', []):
        for gsm in rnd.get('echo_gsms', []):
            for obj in gsm.get('objects', []):
                iid = obj.get('instanceId')
                if iid and iid > 40:
                    ids.add(iid)

    return ids


def templatize_prompt_lifecycle(lifecycle, zone_ids=None):
    """
    Templatize a prompt lifecycle: replace instance IDs with $var_N.

    Returns (templatized_lifecycle, id_map).
    """
    if zone_ids is None:
        zone_ids = set()

    # Collect IDs in order of appearance
    all_ids = []
    seen = set()

    def add_id(val):
        if val not in seen and val > 2 and val not in zone_ids:
            all_ids.append(val)
            seen.add(val)

    # Scan initial request first (defines canonical ordering)
    _collect_ordered(lifecycle['initial_req'].get('promptData'), add_id)

    # Then rounds
    for rnd in lifecycle.get('rounds', []):
        _collect_ordered(rnd['response'].get('promptData'), add_id)
        for gsm in rnd.get('echo_gsms', []):
            for obj in gsm.get('objects', []):
                add_id(obj.get('instanceId', 0))
        if rnd.get('echo_req'):
            _collect_ordered(rnd['echo_req'].get('promptData'), add_id)

    id_map = {orig: f"$var_{i}" for i, orig in enumerate(all_ids, 1)}

    # Deep-replace through the lifecycle
    result = {
        'category': lifecycle['category'],
        'initial_req_promptData': _replace_ids_deep(lifecycle['initial_req'].get('promptData'), id_map),
        'rounds': [],
    }

    for rnd in lifecycle.get('rounds', []):
        t_round = {
            'response_promptData': _replace_ids_deep(rnd['response'].get('promptData'), id_map),
            'echo_gsms': [_replace_ids_deep(_extract_conformance_gsm(g), id_map) for g in rnd.get('echo_gsms', [])],
        }
        if rnd.get('echo_req'):
            t_round['echo_req_promptData'] = _replace_ids_deep(rnd['echo_req'].get('promptData'), id_map)
        result['rounds'].append(t_round)

    if lifecycle.get('submit'):
        result['submit_promptData'] = _replace_ids_deep(lifecycle['submit'].get('promptData'), id_map)

    return result, id_map


def _collect_ordered(obj, add_fn, key=None):
    """Walk a JSON structure collecting integers that look like instance IDs."""
    if isinstance(obj, dict):
        for k, v in obj.items():
            _collect_ordered(v, add_fn, k)
    elif isinstance(obj, list):
        for item in obj:
            _collect_ordered(item, add_fn, key)
    elif isinstance(obj, int) and obj > 40 and key not in _PROMPT_SKIP_KEYS:
        add_fn(obj)


def _extract_conformance_gsm(frame):
    """Extract conformance-relevant fields from a GSM frame."""
    return {
        'objects': frame.get('objects', []),
        'annotations': frame.get('annotations', []),
        'persistentAnnotations': frame.get('persistentAnnotations', []),
        'updateType': frame.get('updateType'),
    }
```

- [ ] **Step 2: Extend cmd_template to handle prompt categories**

Modify `cmd_template` to detect whether the category is a prompt type and use the prompt templatizer:

```python
def cmd_template(args):
    """Extract + templatize a segment."""
    if not args:
        print("Usage: segments.py template <category> [session] [--index N]", file=sys.stderr)
        sys.exit(1)
    category = args[0]
    session = None
    target_index = 0

    i = 1
    while i < len(args):
        if args[i] == "--index" and i + 1 < len(args):
            target_index = int(args[i + 1])
            i += 2
        else:
            session = args[i]
            i += 1

    path = find_md_jsonl(session)
    frames = load_frames(path)

    # Check if this is a prompt category
    if category in ('DeclareAttackers', 'DeclareBlockers', 'Group'):
        _template_prompt(frames, path, category, target_index)
        return

    # Original zone-transfer template logic (unchanged)
    segments = find_segments_by_category(frames, category)
    # ... rest of existing code ...
```

Add the prompt template function:

```python
def _template_prompt(frames, path, category, target_index):
    """Templatize a prompt lifecycle."""
    prompt_cats = find_prompt_lifecycles(frames)

    if category not in prompt_cats:
        print(f"No {category} lifecycles found", file=sys.stderr)
        sys.exit(1)

    lifecycles = prompt_cats[category]
    if target_index >= len(lifecycles):
        print(f"Only {len(lifecycles)} lifecycles, index {target_index} out of range", file=sys.stderr)
        sys.exit(1)

    lc = lifecycles[target_index]
    zone_ids = collect_zone_ids(frames)
    templatized, id_map = templatize_prompt_lifecycle(lc, zone_ids)

    print(f"# Session: {session_name(path)}")
    print(f"# Category: {category} (prompt lifecycle)")
    print(f"# Frames: {lc['start_index']}..{lc['end_index']}")
    print(f"# Rounds: {len(lc['rounds'])}")
    print(f"# ID mapping:")
    for orig, var in sorted(id_map.items(), key=lambda x: x[1]):
        print(f"#   {var} = {orig}")
    print()
    print(json.dumps(templatized, indent=2))
```

- [ ] **Step 3: Add prompt diff command**

Add a `diff_prompt` function that compares engine prompt output against templatized recording:

```python
def diff_prompt_lifecycle(template, engine):
    """
    Diff a templatized recording prompt lifecycle against engine output.

    Both should be dicts with:
      initial_req_promptData, rounds[].response_promptData,
      rounds[].echo_req_promptData, rounds[].echo_gsms

    Returns list of diff entries.
    """
    diffs = []

    # Diff initial request
    t_init = template.get('initial_req_promptData', {})
    e_init = engine.get('initial_req_promptData', {})
    _diff_json_recursive(t_init, e_init, 'initial_req', diffs)

    # Diff each round
    t_rounds = template.get('rounds', [])
    e_rounds = engine.get('rounds', [])
    for ri in range(max(len(t_rounds), len(e_rounds))):
        if ri >= len(t_rounds):
            diffs.append({'path': f'rounds[{ri}]', 'type': 'extra_engine', 'engine': e_rounds[ri]})
            continue
        if ri >= len(e_rounds):
            diffs.append({'path': f'rounds[{ri}]', 'type': 'extra_recording', 'recording': t_rounds[ri]})
            continue

        t_rnd = t_rounds[ri]
        e_rnd = e_rounds[ri]

        # Diff echo request
        if 'echo_req_promptData' in t_rnd or 'echo_req_promptData' in e_rnd:
            _diff_json_recursive(
                t_rnd.get('echo_req_promptData', {}),
                e_rnd.get('echo_req_promptData', {}),
                f'rounds[{ri}].echo_req', diffs,
            )

        # Diff echo GSMs
        t_gsms = t_rnd.get('echo_gsms', [])
        e_gsms = e_rnd.get('echo_gsms', [])
        for gi in range(max(len(t_gsms), len(e_gsms))):
            if gi < len(t_gsms) and gi < len(e_gsms):
                _diff_json_recursive(t_gsms[gi], e_gsms[gi], f'rounds[{ri}].echo_gsms[{gi}]', diffs)

    return diffs


def _diff_json_recursive(recording, engine, path, diffs):
    """Recursively diff two JSON structures, recording field-level differences."""
    if type(recording) != type(engine):
        diffs.append({'path': path, 'type': 'type_mismatch', 'recording': recording, 'engine': engine})
        return

    if isinstance(recording, dict):
        all_keys = set(recording.keys()) | set(engine.keys())
        for key in sorted(all_keys):
            r_val = recording.get(key)
            e_val = engine.get(key)
            if r_val is None and e_val is not None:
                diffs.append({'path': f'{path}.{key}', 'type': 'missing_in_recording', 'engine': e_val})
            elif r_val is not None and e_val is None:
                diffs.append({'path': f'{path}.{key}', 'type': 'missing_in_engine', 'recording': r_val})
            elif r_val != e_val:
                _diff_json_recursive(r_val, e_val, f'{path}.{key}', diffs)
    elif isinstance(recording, list):
        for i in range(max(len(recording), len(engine))):
            if i >= len(recording):
                diffs.append({'path': f'{path}[{i}]', 'type': 'extra_engine', 'engine': engine[i]})
            elif i >= len(engine):
                diffs.append({'path': f'{path}[{i}]', 'type': 'extra_recording', 'recording': recording[i]})
            elif recording[i] != engine[i]:
                _diff_json_recursive(recording[i], engine[i], f'{path}[{i}]', diffs)
    else:
        if recording != engine:
            # Skip $var_N vs concrete ID mismatches (unbound template vars)
            if isinstance(recording, str) and recording.startswith('$var_'):
                return
            diffs.append({'path': path, 'type': 'value_mismatch', 'recording': recording, 'engine': engine})
```

- [ ] **Step 4: Test templatization manually**

```bash
just tape segment template DeclareAttackers 2026-03-06_22-37-41 > /tmp/da-template.json
cat /tmp/da-template.json | python3 -m json.tool | head -40
```

Expected: templatized lifecycle JSON with `$var_N` for instance IDs, `legalDamageRecipients` preserved with `playerSystemSeatId` literal.

- [ ] **Step 5: Commit**

```bash
git add tools/tape/segments.py
git commit -m "feat(conformance): prompt lifecycle templatization and diff engine

Template and diff commands now handle DeclareAttackers, DeclareBlockers,
and Group prompt categories. Templatizes instance IDs in prompt fields,
preserves structural fields (seat IDs, booleans, enums).

Diff produces field-level comparison with path-based reporting."
```

---

## Task 5: Engine-side prompt serialization

**Files:**
- Create: `matchdoor/src/main/kotlin/leyline/conformance/PromptSerializer.kt`

Serialize engine prompt messages to the same JSON format as the recording decoder for diffing.

- [ ] **Step 1: Create PromptSerializer**

```kotlin
package leyline.conformance

import com.google.protobuf.util.JsonFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Serializes engine prompt messages to recording-compatible JSON.
 *
 * Uses [JsonFormat.printer] for lossless proto→JSON conversion,
 * matching the format used by [RecordingDecoder] for recording data.
 * This ensures engine output and recording templates can be diffed
 * field-by-field without format mismatches.
 */
object PromptSerializer {

    private val printer = JsonFormat.printer()
        .omittingInsignificantWhitespace()
        .preservingProtoFieldNames()

    fun serialize(msg: DeclareAttackersReq): JsonElement =
        Json.parseToJsonElement(printer.print(msg))

    fun serialize(msg: DeclareAttackersResp): JsonElement =
        Json.parseToJsonElement(printer.print(msg))

    fun serialize(msg: DeclareBlockersReq): JsonElement =
        Json.parseToJsonElement(printer.print(msg))

    fun serialize(msg: DeclareBlockersResp): JsonElement =
        Json.parseToJsonElement(printer.print(msg))

    fun serialize(msg: GroupReq): JsonElement =
        Json.parseToJsonElement(printer.print(msg))

    fun serialize(msg: GroupResp): JsonElement =
        Json.parseToJsonElement(printer.print(msg))
}
```

- [ ] **Step 2: Build and verify**

Run: `just build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add matchdoor/src/main/kotlin/leyline/conformance/PromptSerializer.kt
git commit -m "feat(conformance): PromptSerializer for engine-side prompt JSON

Lossless proto→JSON using JsonFormat.printer(), matching RecordingDecoder
format for field-level diffing."
```

---

## Task 6: Conformance test scenario for DeclareAttackers

**Files:**
- Modify: `matchdoor/src/test/kotlin/leyline/conformance/ConformancePipelineTest.kt`
- Modify: `matchdoor/src/test/kotlin/leyline/conformance/MatchFlowHarness.kt` (if needed)

- [ ] **Step 1: Read existing ConformancePipelineTest**

Read `matchdoor/src/test/kotlin/leyline/conformance/ConformancePipelineTest.kt` to understand the pattern for writing engine capture scenarios.

- [ ] **Step 2: Add DeclareAttackers scenario**

Add a new test that exercises the full DeclareAttackers lifecycle and captures prompt messages:

```kotlin
test("DeclareAttackers prompt lifecycle capture") {
    val h = MatchFlowHarness(seed = 42L, deckList = COMBAT_DECK, validating = false)

    h.connectAndKeep()
    h.installScriptedAi(listOf(
        ScriptedAction.PlayLand("Mountain"),
        ScriptedAction.DeclareNoAttackers,
        ScriptedAction.PassPriority,
    ))

    // Setup: play Mountain, cast Raging Goblin (haste)
    h.playLand().shouldBeTrue()
    h.castSpellByName("Raging Goblin").shouldBeTrue()
    h.passPriority()

    // Advance to combat
    h.passPriority()

    // Capture initial DeclareAttackersReq
    val daReqMsg = h.allMessages.lastOrNull { it.hasDeclareAttackersReq() }
    daReqMsg.shouldNotBeNull()

    val initialReq = PromptSerializer.serialize(daReqMsg.declareAttackersReq)

    // Toggle one attacker (iterative DeclareAttackersResp)
    val attackerIid = daReqMsg.declareAttackersReq.attackersList.first().attackerInstanceId
    val echoMsgs = h.toggleAttackers(listOf(attackerIid))

    val echoReqMsg = echoMsgs.firstOrNull { it.hasDeclareAttackersReq() }
    echoReqMsg.shouldNotBeNull()
    val echoReq = PromptSerializer.serialize(echoReqMsg.declareAttackersReq)

    // Submit
    h.submitAttackers()

    // Build lifecycle JSON for conformance diffing
    val lifecycle = buildJsonObject {
        put("category", JsonPrimitive("DeclareAttackers"))
        put("initial_req_promptData", initialReq)
        putJsonArray("rounds") {
            addJsonObject {
                put("echo_req_promptData", echoReq)
                // echo_gsms captured separately if needed
            }
        }
    }

    // Write to build dir for pipeline consumption
    val outFile = File("build/conformance/declare-attackers-lifecycle.json")
    outFile.parentFile.mkdirs()
    outFile.writeText(Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), lifecycle))

    h.shutdown()
}
```

Note: imports needed — `leyline.conformance.PromptSerializer`, `kotlinx.serialization.json.*`, `java.io.File`.

- [ ] **Step 3: Run the test**

Run: `just test-one ConformancePipelineTest`
Expected: PASS, file written to `matchdoor/build/conformance/declare-attackers-lifecycle.json`

- [ ] **Step 4: Verify output has all fields**

```bash
cat matchdoor/build/conformance/declare-attackers-lifecycle.json | python3 -m json.tool | head -30
```

Expected: JSON with `legalDamageRecipients`, `canSubmitAttackers` (or their snake_case equivalents) visible in the initial request.

- [ ] **Step 5: Commit**

```bash
git add matchdoor/src/test/kotlin/leyline/conformance/ConformancePipelineTest.kt
git commit -m "feat(conformance): DeclareAttackers lifecycle capture test

Exercises full prompt lifecycle: initial req → toggle → echo → submit.
Captures engine output as lifecycle JSON for pipeline diffing."
```

---

## Task 7: Conform recipe and validation

**Files:**
- Modify: `just/test.just`

- [ ] **Step 1: Verify the existing `just conform` recipe works for prompt categories**

The existing recipe derives filenames from the category and runs `segments.py template` then `segments.py diff`. For prompt categories, `segments.py template` now handles them (Task 4). Check if the recipe needs changes or if it works as-is.

The main difference: engine output is a lifecycle JSON (not a single-frame JSON). The diff command in `segments.py` needs to detect whether the input is a prompt lifecycle or a zone-transfer frame and dispatch accordingly.

- [ ] **Step 2: Add lifecycle detection to cmd_diff**

In `segments.py`, modify `cmd_diff` to detect prompt lifecycle templates:

```python
def cmd_diff(args):
    # ... existing arg parsing ...

    template_path = args[0]
    engine_path = args[1]

    with open(template_path) as f:
        # Skip comment lines (# Session: ..., # ID mapping: ...)
        lines = [l for l in f if not l.startswith('#')]
        template = json.loads(''.join(lines))

    with open(engine_path) as f:
        engine = json.loads(f.read())

    # Detect prompt lifecycle vs zone-transfer frame
    if 'initial_req_promptData' in template:
        diffs = diff_prompt_lifecycle(template, engine)
        _report_prompt_diffs(diffs, json_mode, golden_path)
        return

    # ... existing zone-transfer diff logic ...
```

Add the reporter:

```python
def _report_prompt_diffs(diffs, json_mode=False, golden_path=None):
    """Report prompt lifecycle diff results."""
    if json_mode:
        print(json.dumps({'diffs': diffs, 'count': len(diffs)}, indent=2))
        return

    if not diffs:
        print("PASS — all prompt fields match recording")
        sys.exit(0)

    print(f"FAIL — {len(diffs)} difference(s):")
    print()
    for d in diffs:
        path = d.get('path', '?')
        dtype = d.get('type', '?')
        if dtype == 'missing_in_engine':
            print(f"  {path}: MISSING in engine (recording: {d.get('recording')})")
        elif dtype == 'missing_in_recording':
            print(f"  {path}: EXTRA in engine (engine: {d.get('engine')})")
        elif dtype == 'value_mismatch':
            print(f"  {path}: recording={d.get('recording')} engine={d.get('engine')}")
        elif dtype == 'type_mismatch':
            print(f"  {path}: type mismatch recording={type(d.get('recording')).__name__} engine={type(d.get('engine')).__name__}")
        else:
            print(f"  {path}: {dtype}")

    if golden_path:
        # Golden regression check (same as existing)
        try:
            with open(golden_path) as f:
                golden = json.loads(f.read())
            golden_count = golden.get('count', 0)
            if len(diffs) > golden_count:
                print(f"\nREGRESSION: {len(diffs)} diffs > golden {golden_count}")
                sys.exit(2)
            elif len(diffs) < golden_count:
                print(f"\nIMPROVED: {len(diffs)} diffs < golden {golden_count}")
        except FileNotFoundError:
            pass

    sys.exit(1)
```

- [ ] **Step 3: Test end-to-end**

```bash
# Generate template from recording
just tape segment template DeclareAttackers 2026-03-06_22-37-41 > /tmp/conform-declareattackers-template.json

# Run engine test to generate lifecycle JSON
just test-one ConformancePipelineTest

# Diff
python3 tools/tape/segments.py diff /tmp/conform-declareattackers-template.json matchdoor/build/conformance/declare-attackers-lifecycle.json
```

Expected: diff output showing field-level comparison. Some diffs expected (our engine vs recording).

- [ ] **Step 4: Commit**

```bash
git add tools/tape/segments.py just/test.just
git commit -m "feat(conformance): prompt lifecycle diffing in conform pipeline

cmd_diff detects prompt lifecycle templates and uses field-level recursive
JSON diffing. Reports path-based differences with PASS/FAIL/REGRESSION."
```

---

## Task 8: Validate with All Attack diagnosis

This is the payoff — use the pipeline to diagnose the All Attack issue.

- [ ] **Step 1: Run the full pipeline**

```bash
# List prompt lifecycles in the combat recording
just tape segment list 2026-03-06_22-37-41

# Show first DeclareAttackers lifecycle with full fields
just tape segment show DeclareAttackers 2026-03-06_22-37-41

# Compare against our engine
just conform DeclareAttackers 2026-03-06_22-37-41
```

- [ ] **Step 2: Document the diff**

The diff output should show exactly which fields differ between our engine's DeclareAttackersReq and the recording. This is the ground truth for fixing All Attack — no more guessing.

- [ ] **Step 3: Commit puzzle and spec**

```bash
git add puzzles/all-attack.pzl docs/superpowers/specs/2026-03-14-prompt-conformance-pipeline-design.md docs/superpowers/plans/2026-03-14-prompt-conformance-pipeline.md
git commit -m "docs: prompt conformance pipeline spec, plan, and all-attack puzzle"
```
