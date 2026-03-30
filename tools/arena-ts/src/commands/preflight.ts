import { checkAccessibility, findMtgaWindow } from "../input";
import { currentScene, playerLogStatus } from "../scene";
import { REFERENCE_WIDTH } from "../window";

type Status = "PASS" | "WARN" | "FAIL";

function line(status: Status, msg: string) {
  console.log(`  ${status}  ${msg}`);
}

async function checkMtga(): Promise<Status> {
  const proc = Bun.spawnSync({ cmd: ["pgrep", "-x", "MTGA"] });
  if (proc.exitCode === 0) {
    const pid = proc.stdout.toString().trim().split("\n")[0];
    line("PASS", `MTGA running (pid ${pid})`);
    return "PASS";
  }
  line("FAIL", "MTGA not running");
  return "FAIL";
}

async function checkAccessibilityPerms(): Promise<Status> {
  const granted = await checkAccessibility();
  if (granted) {
    line("PASS", "accessibility granted");
    return "PASS";
  }
  line("FAIL", "accessibility not granted — enable in System Settings > Privacy > Accessibility");
  return "FAIL";
}

async function checkWindow(): Promise<Status> {
  const bounds = await findMtgaWindow();
  if (!bounds) {
    line("FAIL", "MTGA window not found");
    return "FAIL";
  }
  const sizeNote = bounds.w === REFERENCE_WIDTH
    ? ""
    : ` (expected width ${REFERENCE_WIDTH})`;
  line("PASS", `window ${bounds.w}x${bounds.h} at ${bounds.x},${bounds.y}${sizeNote}`);
  return "PASS";
}

async function checkDisplay(): Promise<Status> {
  const proc = Bun.spawnSync({
    cmd: ["system_profiler", "SPDisplaysDataType"],
    stderr: "pipe",
  });
  const out = proc.stdout.toString();
  const match = out.match(/Resolution:\s*(\d+)\s*x\s*(\d+)/);
  if (match) {
    const retina = out.includes("Retina") ? " @2x" : "";
    line("PASS", `display ${match[1]}x${match[2]}${retina}`);
    return "PASS";
  }
  line("WARN", "could not detect display resolution");
  return "WARN";
}

async function checkPlayerLog(): Promise<Status> {
  const info = playerLogStatus();
  if (!info.exists) {
    line("WARN", `Player.log not found (${info.path})`);
    return "WARN";
  }
  const age = info.age_s!;
  if (age > 600) {
    line("WARN", `Player.log stale (${age}s ago)`);
    return "WARN";
  }
  line("PASS", `Player.log (${age}s ago)`);
  return "PASS";
}

async function checkLeylineServer(): Promise<Status> {
  try {
    const resp = await fetch("http://localhost:8090/api/state", {
      signal: AbortSignal.timeout(2000),
    });
    if (resp.ok) {
      line("PASS", "leyline server responding");
      return "PASS";
    }
    line("WARN", `leyline server returned ${resp.status}`);
    return "WARN";
  } catch {
    line("WARN", "leyline server not running (ok for real server)");
    return "WARN";
  }
}

async function checkScene(): Promise<Status> {
  const scene = await currentScene();
  if (scene) {
    line("PASS", `scene: ${scene.scene}`);
    return "PASS";
  }
  line("WARN", "no scene detected in Player.log");
  return "WARN";
}

export async function preflightCommand(args: string[]): Promise<void> {
  if (args.includes("--help") || args.includes("-h")) {
    console.log("arena preflight — check system readiness for automation\n");
    console.log("Checks: MTGA process, accessibility, window, display, Player.log, server, scene.");
    console.log("Exit 0 = ready, exit 2 = not ready (FAIL checks).");
    return;
  }

  const json = args.includes("--json");

  const results: { check: string; status: Status }[] = [];

  const checks: [string, () => Promise<Status>][] = [
    ["mtga", checkMtga],
    ["accessibility", checkAccessibilityPerms],
    ["window", checkWindow],
    ["display", checkDisplay],
    ["player_log", checkPlayerLog],
    ["leyline_server", checkLeylineServer],
    ["scene", checkScene],
  ];

  if (!json) console.log("arena preflight");

  for (const [name, fn] of checks) {
    const status = await fn();
    results.push({ check: name, status });
  }

  const hasFail = results.some((r) => r.status === "FAIL");

  if (json) {
    console.log(JSON.stringify({ ok: !hasFail, checks: results }));
  } else {
    console.log(hasFail ? "  Not ready." : "  Ready.");
  }

  if (hasFail) process.exitCode = 2;
}
