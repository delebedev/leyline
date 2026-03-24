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

- MTGA running and connected to leyline server (`just serve`)
- `ffmpeg` installed (`brew install ffmpeg`)
- Server up: `curl -s http://localhost:8090/api/state` returns JSON

## Steps

### 1. Navigate to the right screen

Use `arena ocr` to check current state. Navigate as needed:
- For gameplay: lobby → Find Match → Bot Match → deck → Play
- For puzzle: set `game.puzzle = "puzzles/<name>.pzl"` → `just serve` → lobby → Sparky → Play
- For a specific bug: follow the repro steps first

### 2. Start recording

```bash
ffmpeg -f avfoundation -framerate 30 -i "Capture screen 0" \
  -c:v libx264 -preset ultrafast -crf 23 -y /tmp/<name>-raw.mp4 \
  > /tmp/ffmpeg.log 2>&1 &
echo "ffmpeg PID: $!"
```

This captures the full screen. Recording runs in background while you play through the scenario.

### 3. Play through the scenario

Run arena commands to drive gameplay. The recording captures everything live.

### 4. Stop recording

Wait for the result/victory screen to fully render before stopping:

```bash
sleep 5   # let result screen animate
kill -INT <ffmpeg_pid>
sleep 2   # let ffmpeg finalize
```

**Don't stop too early** — wait for victory/defeat screen to render. Common mistake is killing ffmpeg right after `gameOver:true` before the client shows the result.

### 5. Compress

Raw capture is large (full screen, 30fps). Compress before upload:

```bash
ffmpeg -i /tmp/<name>-raw.mp4 \
  -c:v libx264 -preset slow -crf 30 -vf "scale=1280:-2" -an \
  -y /tmp/<name>.mp4 2>&1 | tail -3
```

Typical result: 50MB raw → 3MB compressed. Target <10MB for GitHub embeds.

### 6. Upload

```bash
~/.claude/skills/screenshot-upload/upload.sh /tmp/<name>.mp4
```

Returns a public R2 URL.

### 7. Embed

In PR/issue body or comment:

```markdown
https://pub-ee18c3c0efd64ad5967c2972fae3edd3.r2.dev/<filename>.mp4
```

GitHub renders MP4 URLs as inline video players — no special markup needed. Just paste the URL on its own line.

## Tips

- **Play actions during recording** — click through turns, play cards, trigger the feature. The recording is live, not a replay.
- **Playtest first, record second.** Confirm the puzzle/scenario works before starting ffmpeg. Don't waste a recording on a broken run.
- **Full screen capture** — ffmpeg captures everything, not just the MTGA window. Keep other windows minimized.
- **Max 10MB** for GitHub embeds. With crf 30 + scale 1280, that's several minutes of footage.
- **No audio** — `-an` strips audio. Arena sound effects aren't useful for demos.
