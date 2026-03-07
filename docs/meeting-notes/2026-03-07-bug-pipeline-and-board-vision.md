# 2026-03-07 — Bug Pipeline & Board Vision Investment

## Context

- Sealed implementation landed (feat/sealed-courses, 12 commits). Agent drove it nearly end-to-end with minimal handholding.
- 12 open bugs, 3 diagnosed with fix plans (#30 combat echo-back, #38 phase indicators, #31 keyword visuals). Pipeline front-loaded with investigation, starved of execution.
- Dev-loop skill (reproduce → diagnose → plan-fix → implement → verify) exists but the playing-games loop is fragile: unreliable drag, no structured board state, manual correlation of visual + protocol + logs.

## Decisions

### Investment priorities for the bug-fix pipeline

Three pillars to make the play-loop reliable and observable, then bugs become cheap to fix in volume:

1. **Reliable drag-and-drop** (#54) — biggest blocker to autonomous play. Unity eats clicks as drag-starts.
2. **Structured board state** — merge debug API + OCR + zone geometry into one queryable tool. Agent asks one question, gets everything.
3. **UI ↔ engine state bridge** — correlate what the screen shows with what the debug accumulator knows and what player.log reports. Currently three disconnected sources.

### `arena board` command (building now)

Merge existing pieces into one call:
- `/api/id-map` — instanceId, name, zone, P/T, tapped, actions
- `/api/state` — phase, turn, active player, life
- `arena ocr` — text positions on screen
- Zone rects from `arena-annotate` (hardcoded 960×568)

Output: single JSON with hand[], battlefield[], opponent_battlefield[], stack[], available_actions[], phase/turn/life. Each card entry has instanceId + name + estimated screen coords where possible.

No ML needed. Protocol-informed zone mapping: debug API knows card counts and names per zone, OCR anchors text to pixel positions, zone rects provide spatial buckets.

### Deck creation — parked

Works for local serve (pre-built test decks). Proxy golden recording flow is slower/more steps. Not investing now — different workflow (FD features, not MD bugs).

## Future: YouTube training data pipeline

For when Option 1 (protocol-informed mapping) breaks down — e.g. two 2/2 creatures side by side, need pixel-level card detection.

**Idea:** Use YouTube MTGA gameplay as free training data for a card bounding box model.

Pipeline:
```
yt-dlp (1080p) → ffmpeg (1fps + scene-change detection) → dedupe/filter → annotate → training set
```

Labeling strategy:
- **Our recordings** (`just serve-proxy`): protocol state is timestamped alongside the game. Match frame timestamps to GSM timestamps → automatic bbox labels from zone contents + card counts. Gold labels.
- **YouTube videos**: no protocol data, but zone geometry is identical. Bootstrap model from our labeled data, transfers directly. Also: OCR P/T text → card anchors, mana symbols → hand positions (weak/semi-supervised labels).

Model options:
- YOLOv8-nano or Apple CreateML object detection → CoreML → Apple Neural Engine, <10ms inference
- Classes: `card-hand`, `card-battlefield`, `card-opponent`, `stack-item`
- Training set: ~200-500 annotated screenshots to start

Standalone script in `tooling/scripts/` or `bin/training-pipeline`. Zero coupling to main codebase. Spike separately from `arena board`.

## Bug pipeline status

| Issue | State | Next step |
|---|---|---|
| #30 Combat echo-back | diagnosed, fix plan written | Implement (4 files, unified pattern) |
| #38 Phase indicators | reproduced + diagnosed | Implement (priority GSM for non-active player) |
| #31 Keyword visuals | diagnosed (2 rounds) | Needs LayeredEffect annotation investigation |
| #39 Autotap dorks | reproduced + diagnosed | Forge-core fix (ComputerUtilMana) |
| #35 Land tapping | investigated, not diagnosed | Blocked on proxy recording with dual lands |
| #37 Targeting highlights | unfiled investigation | Needs reproduce attempt |
| #33 Combat damage anim | raw bug | Needs reproduce |
| #40 NPE null source zone | raw bug | Needs reproduce |

## Action items

- [x] Document pipeline discussion (this file)
- [ ] Build `arena board` command
- [ ] Execute #30 or #38 as first full dev-loop proof
- [ ] Spike YouTube training pipeline (separate, later)
