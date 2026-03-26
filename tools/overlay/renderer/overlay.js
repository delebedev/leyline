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
