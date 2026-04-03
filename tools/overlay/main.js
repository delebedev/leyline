import { app, BrowserWindow, screen, globalShortcut } from "electron";
import { execFileSync } from "child_process";
import { join } from "path";

const OVERLAY_WIDTH = 300;
const TRACK_INTERVAL = 1000;
const BOUNDS_BIN = join(import.meta.dirname, "native", "window-bounds");

let win;
let interactive = false;
let trackTimer = null;

function toggleInteractive() {
  interactive = !interactive;
  win.setIgnoreMouseEvents(!interactive, { forward: true });
  win.webContents.send("interactive", interactive);
}

/** Get MTGA window bounds via compiled Swift helper. Returns {x, y, w, h} or null if not visible. */
function getMtgaBounds() {
  try {
    const out = execFileSync(BOUNDS_BIN, { timeout: 500 }).toString().trim();
    if (!out) return null;
    const [x, y, w, h] = out.split(",").map(Number);
    if ([x, y, w, h].some(isNaN)) return null;
    return { x, y, w, h };
  } catch {
    return null;
  }
}

/** Reposition overlay to left edge of MTGA window, or hide if MTGA not visible. */
function trackWindow() {
  const bounds = getMtgaBounds();
  if (bounds) {
    if (!win.isVisible()) win.show();
    win.setBounds({ x: bounds.x, y: bounds.y, width: OVERLAY_WIDTH, height: bounds.h });
  } else {
    if (win.isVisible()) win.hide();
  }
}

function createWindow() {
  win = new BrowserWindow({
    width: OVERLAY_WIDTH,
    height: 600,
    x: 0,
    y: 0,
    transparent: true,
    frame: false,
    alwaysOnTop: true,
    skipTaskbar: true,
    hasShadow: false,
    show: false,
    webPreferences: {
      nodeIntegration: true,
      contextIsolation: false,
    },
  });

  win.setAlwaysOnTop(true, "screen-saver");
  win.setIgnoreMouseEvents(true, { forward: true });
  win.loadFile(join(import.meta.dirname, "renderer", "index.html"));
  globalShortcut.register("CommandOrControl+Shift+D", toggleInteractive);

  trackWindow();
  trackTimer = setInterval(trackWindow, TRACK_INTERVAL);
}

app.whenReady().then(createWindow);
app.on("window-all-closed", () => app.quit());
app.on("will-quit", () => {
  globalShortcut.unregisterAll();
  if (trackTimer) clearInterval(trackTimer);
});
