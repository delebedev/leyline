---
name: video-demo
description: Record a short MP4 demo of MTGA gameplay for GitHub PR/issue attachments. Use when you need visual evidence of a feature or bug.
---

## What I do

Record a short MP4 video of the MTGA window and upload it for embedding in GitHub PRs/issues.

## When to use me

- "record a demo for the PR"
- "attach a video showing the sealed flow"
- "capture a repro video of this bug"
- Before merging a feature PR that has visible UI changes

## Prerequisites

- MTGA running and connected to leyline server (`just serve` or `just serve-proxy`)
- `ffmpeg` installed (`brew install ffmpeg`)
- Server up: `curl -s http://localhost:8090/api/state` returns JSON

## Steps

### 1. Navigate to the right screen

Use `arena ocr` to check current state. Navigate as needed:
- For gameplay: start a bot match via Find Match flow
- For sealed: join sealed event, open packs, build deck
- For a specific bug: follow the repro steps first

### 2. Record

```bash
bin/arena-record --duration <seconds> --fps 5 --out /tmp/<name>.mp4
```

**Duration guide:**
- Bug repro: 5-10s (just the failing moment)
- Feature demo: 10-20s (show the flow)
- Full lifecycle: 30s max (join → play → result)

**Defaults:** 10s duration, 5fps, `/tmp/arena-repro.mp4`

The tool activates MTGA, captures via `screencapture -R` at the exact window rect, resizes to 960x568 logical, encodes H.264 MP4. Output is ~30KB/s, GitHub-embeddable.

### 3. Upload

```bash
~/.claude/skills/screenshot-upload/upload.sh /tmp/<name>.mp4
```

Returns a public R2 URL.

### 4. Embed

In PR/issue body or comment:

```markdown
https://pub-ee18c3c0efd64ad5967c2972fae3edd3.r2.dev/<filename>.mp4
```

GitHub renders MP4 URLs as inline video players — no special markup needed. Just paste the URL on its own line.

### Optional: screenshot too

For a static thumbnail alongside the video:

```bash
bin/arena capture --out /tmp/<name>.png
~/.claude/skills/screenshot-upload/upload.sh /tmp/<name>.png
```

## Tips

- **Play actions during recording** — click through turns, play cards, trigger the feature. The recording is live, not a replay.
- **5fps is fine** — card games don't need 30fps. Keeps file small (~150KB for 5s).
- **Max 10MB** for GitHub embeds. At 5fps/crf28, that's ~5 minutes of footage.
- **Screen must not be locked** — `arena-record` warns if screen saver is running.
