# Debug HUD Overlay

Standalone Electron app that renders a debug HUD on top of MTGA. Shows instance ID map and server logs. Always running — connects to debug API when available, grays out when not.

## Architecture

```
Debug API (:8090)              Electron App (tools/overlay/)
┌──────────────┐     SSE       ┌─────────────────────────┐
│ /api/events  │──────────────▶│ Main process (main.js)  │
│ /api/id-map  │◀────REST─────│   - SSE listener         │
│ /api/logs    │               │   - globalShortcut       │
│ /api/state   │               │   - auto-reconnect       │
└──────────────┘               │                          │
                               │ Renderer (HTML/CSS/JS)   │
                               │   - ID map panel         │
                               │   - Log panel            │
                               │   - transparent bg       │
                               └─────────────────────────┘
```

### BrowserWindow config

```js
new BrowserWindow({
  transparent: true,
  frame: false,
  alwaysOnTop: true,
  skipTaskbar: true,
  width: 300,
  height: screenHeight,
  x: screenWidth - 300,
  y: 0,
})
win.setAlwaysOnTop(true, "screen-saver")
win.setIgnoreMouseEvents(true)
```

## Data flow

- **SSE primary** — connect to `/api/events`, listen for `log`, `state`, `message` event types
- **REST on state change** — when `state` event arrives, fetch `/api/id-map?active=true` to refresh instance table
- **Logs stream** — `log` SSE events feed directly into the log panel ring buffer

## Connection lifecycle

- On launch: poll `GET /api/state` every 2s
- On first success: open SSE to `/api/events`, switch to live mode
- On SSE disconnect: show "disconnected" state (dim panels, keep last data), resume 2s polling
- On reconnect: clear stale data, resume live mode
- Overlay stays open through any number of server restart cycles

## Layout

Right edge of screen, full height, 300px wide.

```
┌──────────────────┐
│ ● Main1 · T3     │  status bar: phase + turn
├──────────────────┤
│ ID Map           │  scrollable table
│ 123 Bolt   BF ○  │  iid | name | zone | tapped
│ 456 Bear   BF ●  │  ● = tapped, ○ = untapped
│ 789 Think  GY    │  color-coded by zone
├──────────────────┤
│ Logs             │  auto-scroll, ring buffer (200)
│ 10:23 WARN …    │  color by level
│ 10:24 INFO …    │  red/yellow/white/gray
│ 10:24 DEBUG …   │
└──────────────────┘
```

Background: `rgba(0,0,0,0.75)`. Monospace font. Compact rows.

## Interaction toggle

`Cmd+Shift+D` via Electron `globalShortcut`:

- **Click-through** (default): `setIgnoreMouseEvents(true)`. Auto-scrolls logs. No interaction.
- **Interactive**: `setIgnoreMouseEvents(false)`. Subtle green border glow. Scroll logs, sort/filter ID map, click level badges to toggle log levels. Text filter for ID map.
- Second press returns to click-through.

## Panels

### Instance ID map

- Source: `GET /api/id-map?active=true` — refreshed on every `state` SSE event
- Columns: instanceId, card name (truncated 12ch), zone shortcode (BF/Hand/GY/Stack/Ex), tapped indicator
- Zone color coding: green=BF, blue=Hand, red=GY, orange=Stack, gray=Exile
- Interactive mode: click column header to sort, text input to filter by name

### Server logs

- Source: `log` SSE events
- Ring buffer: 200 lines max
- Color: red=ERROR, yellow=WARN, white=INFO, gray=DEBUG
- Click-through mode: auto-scroll pinned to bottom
- Interactive mode: free scroll, click level badges to toggle visibility

### Status bar

- Source: `state` SSE events
- Shows: phase shortcode + turn number
- Disconnected state: "⏳ waiting…" in dim text

## Tech stack

- **Runtime:** Bun
- **Window:** Electron
- **Renderer:** vanilla HTML/CSS/JS (no framework)
- **Pattern:** follows forge-web-ui bun config conventions (bunfig.toml, bun.lock)

## File structure

```
tools/overlay/
  package.json         # electron + @types/bun
  bunfig.toml
  main.js              # Electron main process
  renderer/
    index.html         # layout + inline CSS
    overlay.js         # SSE, REST polling, panel rendering
```

## CLI integration

```
just overlay           # starts the overlay app via bun
```

Fails gracefully if Electron not installed (prints install hint). Does NOT require debug server to be running — connects when available.

## Future

- Additional panels (priority log, FD messages, actions available)
- Per-card overlays using detection model (separate effort)
- Tauri migration when Electron weight becomes a problem
