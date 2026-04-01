import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";

// --- DOM refs ---

const playBtn = document.getElementById("play-btn") as HTMLButtonElement;
const restoreBtn = document.getElementById("restore-btn") as HTMLButtonElement;
const hint = document.getElementById("hint") as HTMLElement;
const serverDot = document.querySelector("#status-server .dot") as HTMLElement;
const serverValue = document.getElementById("server-value") as HTMLElement;
const arenaDot = document.querySelector("#status-arena .dot") as HTMLElement;
const arenaValue = document.getElementById("arena-value") as HTMLElement;

const infoBtn = document.getElementById("info-btn") as HTMLButtonElement;
const infoOverlay = document.getElementById("info-overlay") as HTMLElement;
const ghLink = document.getElementById("gh-link") as HTMLAnchorElement;

// --- Info panel ---

infoBtn.addEventListener("click", () => {
  infoOverlay.classList.toggle("hidden");
});

infoOverlay.addEventListener("click", (e) => {
  if (e.target === infoOverlay) {
    infoOverlay.classList.add("hidden");
  }
});

ghLink.addEventListener("click", async (e) => {
  e.preventDefault();
  const { open } = await import("@tauri-apps/plugin-shell");
  await open("https://github.com/delebedev/leyline");
});

// --- State ---

let arenaFound = false;
let arenaSource = "";
let arenaConfigured = false;
let serverState = "Stopped";
let busy = false;

function updateUI() {
  playBtn.disabled = !arenaFound || busy || serverState === "Running";
  restoreBtn.disabled = !arenaFound || busy;

  // Server status
  serverDot.className = "dot";
  switch (serverState) {
    case "Stopped":
      serverDot.classList.add("dot-stopped");
      serverValue.textContent = "Stopped";
      break;
    case "Starting":
      serverDot.classList.add("dot-starting");
      serverValue.textContent = "Starting...";
      break;
    case "Running":
      serverDot.classList.add("dot-running");
      serverValue.textContent = "Running";
      break;
    default:
      serverDot.classList.add("dot-error");
      serverValue.textContent = serverState;
      break;
  }

  // Hint text
  if (serverState === "Running") {
    hint.textContent = "Launch Arena to play";
  } else if (serverState === "Starting") {
    hint.textContent = "";
  } else {
    hint.textContent = "";
  }
}

// --- Arena detection ---

interface ArenaInfo {
  path: string;
  source: string;
  configured: boolean;
}

async function checkArena() {
  try {
    const info = (await invoke("detect_arena")) as ArenaInfo;
    arenaFound = true;
    arenaSource = info.source;
    arenaConfigured = info.configured;
    arenaDot.className = "dot dot-found";
    if (info.configured) {
      arenaValue.textContent = `Configured (${info.source})`;
    } else {
      arenaValue.textContent = `Stock (${info.source})`;
    }
  } catch (e) {
    arenaFound = false;
    arenaDot.className = "dot dot-missing";
    arenaValue.textContent = String(e);
  }
  updateUI();
}

// --- Server state listener ---

interface ServerStateEvent {
  state: string;
  message?: string;
}

listen<ServerStateEvent>("server-state", (event) => {
  const s = event.payload;
  if (s.state === "Error") {
    serverState = s.message || "Error";
  } else {
    serverState = s.state;
  }
  updateUI();
});

// --- Play: configure + start server (player launches Arena themselves) ---

playBtn.addEventListener("click", async () => {
  if (busy) return;
  busy = true;
  updateUI();

  try {
    playBtn.textContent = "Configuring...";
    await invoke("deploy_config");

    playBtn.textContent = "Starting server...";
    await invoke("start_server");

    // Wait for server to become healthy
    await waitForRunning();

    // Refresh arena status (now configured)
    await checkArena();
  } catch (e) {
    serverState = String(e);
  } finally {
    busy = false;
    playBtn.textContent = "Start";
    updateUI();
  }
});

async function waitForRunning(): Promise<void> {
  for (let i = 0; i < 60; i++) {
    if (serverState === "Running") return;
    if (serverState !== "Starting" && serverState !== "Stopped") {
      throw new Error(serverState);
    }
    await sleep(500);
  }
  throw new Error("Server did not start in time");
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

// --- Restore ---

restoreBtn.addEventListener("click", async () => {
  if (busy) return;
  busy = true;
  updateUI();

  try {
    await invoke("stop_server");
    await invoke("restore_arena");
  } catch (e) {
    console.error("Restore failed:", e);
  } finally {
    busy = false;
    await checkArena();
    updateUI();
  }
});

// --- Init ---

async function init() {
  await checkArena();
  try {
    const state = (await invoke("server_status")) as ServerStateEvent;
    serverState =
      state.state === "Error" ? state.message || "Error" : state.state;
  } catch {
    serverState = "Stopped";
  }
  updateUI();
}

init();
