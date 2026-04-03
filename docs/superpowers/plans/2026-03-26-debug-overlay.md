# Debug HUD Overlay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Standalone Electron overlay app that shows instance ID map and server logs on top of MTGA, connected via debug API SSE.

**Architecture:** Bun + Electron app in `tools/overlay/`. Main process manages BrowserWindow (transparent, always-on-top, click-through) and SSE connection to `:8090`. Renderer is vanilla HTML/CSS/JS — no framework. Auto-reconnects when server restarts.

**Tech Stack:** Bun, Electron, vanilla JS, SSE (EventSource)

---

### Task 1: Scaffold Electron app with Bun

**Files:**
- Create: `tools/overlay/package.json`
- Create: `tools/overlay/bunfig.toml`
- Create: `tools/overlay/main.js`
- Create: `tools/overlay/renderer/index.html`

- [ ] **Step 1: Create package.json**

```json
{
  "name": "leyline-overlay",
  "private": true,
  "version": "0.0.0",
  "type": "module",
  "main": "main.js",
  "scripts": {
    "start": "electron ."
  },
  "devDependencies": {
    "electron": "^35.0.0"
  }
}
```

- [ ] **Step 2: Create bunfig.toml**

```toml
[run]
bun = true
```

- [ ] **Step 3: Create main.js — minimal transparent window**

```js
import { app, BrowserWindow, screen } from "electron";
import { join } from "path";

let win;

function createWindow() {
  const { height, width } = screen.getPrimaryDisplay().workAreaSize;

  win = new BrowserWindow({
    width: 300,
    height: height,
    x: width - 300,
    y: 0,
    transparent: true,
    frame: false,
    alwaysOnTop: true,
    skipTaskbar: true,
    hasShadow: false,
    webPreferences: {
      nodeIntegration: true,
      contextIsolation: false,
    },
  });

  win.setAlwaysOnTop(true, "screen-saver");
  win.setIgnoreMouseEvents(true);
  win.loadFile(join(import.meta.dirname, "renderer", "index.html"));
}

app.whenReady().then(createWindow);
app.on("window-all-closed", () => app.quit());
```

- [ ] **Step 4: Create renderer/index.html — empty shell**

```html
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Leyline Overlay</title>
<style>
  * { margin: 0; padding: 0; box-sizing: border-box; }
  body {
    font-family: 'JetBrains Mono', 'Menlo', monospace;
    font-size: 11px;
    background: transparent;
    color: #e0e0e0;
    height: 100vh;
    overflow: hidden;
  }
  #root {
    background: rgba(0, 0, 0, 0.75);
    border-left: 1px solid rgba(255, 255, 255, 0.1);
    height: 100vh;
    display: flex;
    flex-direction: column;
  }
</style>
</head>
<body>
  <div id="root">
    <div id="status">Waiting for server…</div>
  </div>
  <script src="overlay.js"></script>
</body>
</html>
```

- [ ] **Step 5: Install deps and verify launch**

```bash
cd tools/overlay && bun install
bun run start
```

Expected: transparent black strip on right edge of screen showing "Waiting for server…". Clicks pass through to whatever is behind it.

- [ ] **Step 6: Commit**

```bash
git add tools/overlay/
git commit -m "feat(overlay): scaffold Electron app with transparent window"
```

---

### Task 2: Connection lifecycle — auto-reconnect polling + SSE

**Files:**
- Create: `tools/overlay/renderer/overlay.js`

- [ ] **Step 1: Create overlay.js with connection state machine**

```js
const API_BASE = "http://localhost:8090";
const POLL_INTERVAL = 2000;

const state = {
  connected: false,
  phase: null,
  turn: 0,
  eventSource: null,
  pollTimer: null,
};

const statusEl = document.getElementById("status");

function setConnected(connected) {
  state.connected = connected;
  statusEl.textContent = connected ? "" : "Waiting for server…";
  statusEl.style.color = connected ? "#56c596" : "#8888aa";
  document.getElementById("root").style.opacity = connected ? "1" : "0.4";
}

async function tryConnect() {
  try {
    const res = await fetch(`${API_BASE}/api/state`);
    if (!res.ok) return;
    const data = await res.json();
    updateStatus(data);
    setConnected(true);
    startSSE();
    stopPolling();
  } catch {
    // server not up yet
  }
}

function startPolling() {
  if (state.pollTimer) return;
  state.pollTimer = setInterval(tryConnect, POLL_INTERVAL);
}

function stopPolling() {
  if (state.pollTimer) {
    clearInterval(state.pollTimer);
    state.pollTimer = null;
  }
}

function startSSE() {
  if (state.eventSource) return;
  const es = new EventSource(`${API_BASE}/api/events`);

  es.addEventListener("state", (e) => {
    const data = JSON.parse(e.data);
    updateStatus(data);
    refreshIdMap();
  });

  es.addEventListener("log", (e) => {
    const entry = JSON.parse(e.data);
    appendLog(entry);
  });

  es.onerror = () => {
    es.close();
    state.eventSource = null;
    setConnected(false);
    startPolling();
  };

  state.eventSource = es;
}

function updateStatus(data) {
  const phase = data.phase || "—";
  const turn = data.turn || 0;
  statusEl.textContent = `● ${phase} · T${turn}`;
  statusEl.style.color = "#f7d354";
}

// Stubs — implemented in Tasks 3 and 4
function refreshIdMap() {}
function appendLog(_entry) {}

// Boot
setConnected(false);
tryConnect();
startPolling();
```

- [ ] **Step 2: Verify against running debug server**

Start leyline server (`just serve`), then launch overlay (`cd tools/overlay && bun run start`).

Expected: status bar shows phase + turn. On `just stop`, overlay dims and shows "Waiting for server…". On `just serve` again, reconnects automatically.

- [ ] **Step 3: Commit**

```bash
git add tools/overlay/renderer/overlay.js
git commit -m "feat(overlay): connection lifecycle with auto-reconnect SSE"
```

---

### Task 3: Instance ID map panel

**Files:**
- Modify: `tools/overlay/renderer/index.html`
- Modify: `tools/overlay/renderer/overlay.js`

- [ ] **Step 1: Add ID map HTML structure to index.html**

Add inside `#root`, after `#status`:

```html
<div id="idmap-panel">
  <div class="panel-header">ID Map</div>
  <div id="idmap-list"></div>
</div>
```

Add CSS:

```css
#status {
  padding: 6px 8px;
  font-weight: 700;
  font-size: 12px;
  border-bottom: 1px solid rgba(255,255,255,0.1);
  flex-shrink: 0;
}
.panel-header {
  padding: 4px 8px;
  font-weight: 700;
  font-size: 10px;
  text-transform: uppercase;
  letter-spacing: 1px;
  color: #8888aa;
  border-bottom: 1px solid rgba(255,255,255,0.1);
  flex-shrink: 0;
}
#idmap-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border-bottom: 1px solid rgba(255,255,255,0.1);
}
#idmap-list {
  flex: 1;
  overflow-y: auto;
  padding: 2px 0;
}
.id-row {
  display: flex;
  gap: 6px;
  padding: 1px 8px;
  white-space: nowrap;
}
.id-row .iid { color: #8888aa; width: 40px; text-align: right; }
.id-row .name { flex: 1; overflow: hidden; text-overflow: ellipsis; }
.id-row .zone { width: 36px; text-align: center; font-weight: 700; }
.id-row .tap { width: 12px; text-align: center; }

.zone-bf { color: #56c596; }
.zone-hand { color: #4ea8de; }
.zone-gy { color: #e94560; }
.zone-stack { color: #f4845f; }
.zone-exile { color: #8888aa; }
```

- [ ] **Step 2: Implement refreshIdMap() in overlay.js**

Replace the `refreshIdMap` stub:

```js
const idmapList = document.getElementById("idmap-list");

const ZONE_SHORT = {
  Battlefield: "BF",
  Hand: "Hand",
  Graveyard: "GY",
  Stack: "Stk",
  Exile: "Ex",
  Library: "Lib",
  Command: "Cmd",
};

const ZONE_CLASS = {
  Battlefield: "zone-bf",
  Hand: "zone-hand",
  Graveyard: "zone-gy",
  Stack: "zone-stack",
  Exile: "zone-exile",
};

async function refreshIdMap() {
  try {
    const res = await fetch(`${API_BASE}/api/id-map?active=true`);
    if (!res.ok) return;
    const entries = await res.json();
    renderIdMap(entries);
  } catch {
    // server may have just gone away
  }
}

function renderIdMap(entries) {
  idmapList.innerHTML = "";
  for (const e of entries) {
    const zone = e.forgeZone || "?";
    const zoneShort = ZONE_SHORT[zone] || zone.slice(0, 3);
    const zoneClass = ZONE_CLASS[zone] || "zone-exile";
    const tapped = zone === "Battlefield"
      ? (e.protoZone === "Battlefield" ? "○" : "")  // tapped state comes from game-states, simplified here
      : "";
    const name = e.cardName.length > 12 ? e.cardName.slice(0, 11) + "…" : e.cardName;

    const row = document.createElement("div");
    row.className = "id-row";
    row.innerHTML = `
      <span class="iid">${e.instanceId}</span>
      <span class="name">${name}</span>
      <span class="zone ${zoneClass}">${zoneShort}</span>
      <span class="tap">${tapped}</span>
    `;
    idmapList.appendChild(row);
  }
}
```

- [ ] **Step 3: Verify with running server**

Launch overlay with a game in progress. ID map should populate with active instances, color-coded by zone.

- [ ] **Step 4: Commit**

```bash
git add tools/overlay/renderer/
git commit -m "feat(overlay): instance ID map panel with zone color coding"
```

---

### Task 4: Server logs panel

**Files:**
- Modify: `tools/overlay/renderer/index.html`
- Modify: `tools/overlay/renderer/overlay.js`

- [ ] **Step 1: Add logs HTML structure to index.html**

Add inside `#root`, after `#idmap-panel`:

```html
<div id="log-panel">
  <div class="panel-header">Logs</div>
  <div id="log-list"></div>
</div>
```

Add CSS:

```css
#log-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
#log-list {
  flex: 1;
  overflow-y: auto;
  padding: 2px 0;
}
.log-row {
  padding: 1px 8px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.log-error { color: #e94560; }
.log-warn { color: #f7d354; }
.log-info { color: #e0e0e0; }
.log-debug { color: #666680; }
```

- [ ] **Step 2: Implement appendLog() in overlay.js**

Replace the `appendLog` stub:

```js
const logList = document.getElementById("log-list");
const LOG_MAX = 200;
let logAutoScroll = true;

const LOG_CLASS = {
  ERROR: "log-error",
  WARN: "log-warn",
  INFO: "log-info",
  DEBUG: "log-debug",
  TRACE: "log-debug",
};

function appendLog(entry) {
  const row = document.createElement("div");
  row.className = `log-row ${LOG_CLASS[entry.level] || "log-info"}`;

  const time = entry.timestamp
    ? new Date(entry.timestamp).toLocaleTimeString("en", { hour12: false, hour: "2-digit", minute: "2-digit", second: "2-digit" })
    : "";
  const level = (entry.level || "INFO").padEnd(5);
  const msg = entry.message || "";
  row.textContent = `${time} ${level} ${msg}`;
  logList.appendChild(row);

  // Trim ring buffer
  while (logList.children.length > LOG_MAX) {
    logList.removeChild(logList.firstChild);
  }

  // Auto-scroll when in click-through mode
  if (logAutoScroll) {
    logList.scrollTop = logList.scrollHeight;
  }
}
```

- [ ] **Step 3: Verify with running server**

Launch overlay with server running. Logs should stream in real-time, color-coded by level, auto-scrolling.

- [ ] **Step 4: Commit**

```bash
git add tools/overlay/renderer/
git commit -m "feat(overlay): server log panel with ring buffer and auto-scroll"
```

---

### Task 5: Interaction toggle (Cmd+Shift+D)

**Files:**
- Modify: `tools/overlay/main.js`
- Modify: `tools/overlay/renderer/index.html`
- Modify: `tools/overlay/renderer/overlay.js`

- [ ] **Step 1: Add globalShortcut in main.js**

Add after `win.setIgnoreMouseEvents(true)`:

```js
import { app, BrowserWindow, screen, globalShortcut } from "electron";

let interactive = false;

function toggleInteractive() {
  interactive = !interactive;
  win.setIgnoreMouseEvents(!interactive);
  win.webContents.send("interactive", interactive);
}

// Inside createWindow(), after win.loadFile():
globalShortcut.register("CommandOrControl+Shift+D", toggleInteractive);

// In app.on("window-all-closed"):
app.on("will-quit", () => globalShortcut.unregisterAll());
```

Update the existing import line to include `globalShortcut`.

- [ ] **Step 2: Add interactive mode styles to index.html**

Add CSS:

```css
#root.interactive {
  border-left: 2px solid #56c596;
  box-shadow: -4px 0 12px rgba(86, 197, 150, 0.2);
}
#idmap-filter {
  display: none;
  padding: 4px 8px;
  border-bottom: 1px solid rgba(255,255,255,0.1);
}
#idmap-filter input {
  width: 100%;
  background: rgba(255,255,255,0.1);
  border: none;
  color: #e0e0e0;
  font-family: inherit;
  font-size: 11px;
  padding: 2px 6px;
  border-radius: 3px;
  outline: none;
}
.interactive #idmap-filter { display: block; }
```

Add filter input in HTML, inside `#idmap-panel` before `#idmap-list`:

```html
<div id="idmap-filter"><input type="text" placeholder="filter…" id="idmap-filter-input"></div>
```

- [ ] **Step 3: Handle interactive toggle in overlay.js**

Add to overlay.js:

```js
const { ipcRenderer } = require("electron");

const root = document.getElementById("root");
const filterInput = document.getElementById("idmap-filter-input");
let lastIdMapEntries = [];

ipcRenderer.on("interactive", (_event, isInteractive) => {
  if (isInteractive) {
    root.classList.add("interactive");
    logAutoScroll = false;
  } else {
    root.classList.remove("interactive");
    logAutoScroll = true;
    logList.scrollTop = logList.scrollHeight;
    filterInput.value = "";
    renderIdMap(lastIdMapEntries);
  }
});

// Update renderIdMap to cache entries
function renderIdMap(entries) {
  lastIdMapEntries = entries;
  const filter = filterInput.value.toLowerCase();
  const filtered = filter
    ? entries.filter(e => e.cardName.toLowerCase().includes(filter))
    : entries;

  idmapList.innerHTML = "";
  for (const e of filtered) {
    const zone = e.forgeZone || "?";
    const zoneShort = ZONE_SHORT[zone] || zone.slice(0, 3);
    const zoneClass = ZONE_CLASS[zone] || "zone-exile";
    const name = e.cardName.length > 12 ? e.cardName.slice(0, 11) + "…" : e.cardName;

    const row = document.createElement("div");
    row.className = "id-row";
    row.innerHTML = `
      <span class="iid">${e.instanceId}</span>
      <span class="name">${name}</span>
      <span class="zone ${zoneClass}">${zoneShort}</span>
    `;
    idmapList.appendChild(row);
  }
}

filterInput.addEventListener("input", () => renderIdMap(lastIdMapEntries));
```

- [ ] **Step 4: Verify toggle**

Launch overlay. Press Cmd+Shift+D — green border should appear, filter input visible, logs stop auto-scrolling. Press again — returns to click-through, filter clears, logs resume auto-scroll.

- [ ] **Step 5: Commit**

```bash
git add tools/overlay/
git commit -m "feat(overlay): Cmd+Shift+D interaction toggle with filter"
```

---

### Task 6: Justfile integration

**Files:**
- Modify: `justfile`

- [ ] **Step 1: Add overlay recipe to justfile**

Find the tools section of the justfile and add:

```just
# Launch debug overlay (Electron, always-on-top HUD)
overlay:
    @cd tools/overlay && bun run start
```

- [ ] **Step 2: Verify**

```bash
just overlay
```

Expected: overlay launches. If `bun` or `electron` not installed, clear error message.

- [ ] **Step 3: Commit**

```bash
git add justfile
git commit -m "feat: add just overlay recipe"
```

---

### Task 7: Smoke test — full loop

This is a manual verification task, not automated tests. Per `rules/tooling.md`, leaf CLI tools are tested by use.

- [ ] **Step 1: Cold start (no server)**

```bash
just overlay
```

Expected: overlay appears, shows "Waiting for server…", panels dimmed.

- [ ] **Step 2: Start server**

```bash
just serve
```

Expected: overlay connects, status bar shows phase, ID map empty (no match yet).

- [ ] **Step 3: Start a match (puzzle or bot game)**

Expected: ID map populates with cards. Logs stream in.

- [ ] **Step 4: Toggle interaction**

Press Cmd+Shift+D. Type in filter. Scroll logs. Press again — click-through resumes.

- [ ] **Step 5: Kill server**

```bash
just stop
```

Expected: overlay dims, shows "Waiting for server…", keeps last data visible but faded.

- [ ] **Step 6: Restart server**

```bash
just serve
```

Expected: overlay reconnects, clears stale data, resumes live.
