import { app, BrowserWindow, screen, globalShortcut } from "electron";
import { join } from "path";

let win;
let interactive = false;

function toggleInteractive() {
  interactive = !interactive;
  win.setIgnoreMouseEvents(!interactive);
  win.webContents.send("interactive", interactive);
}

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
  globalShortcut.register("CommandOrControl+Shift+D", toggleInteractive);
}

app.whenReady().then(createWindow);
app.on("window-all-closed", () => app.quit());
app.on("will-quit", () => globalShortcut.unregisterAll());
