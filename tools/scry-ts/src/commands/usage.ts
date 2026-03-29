import { readFileSync, existsSync } from "fs";
import { join } from "path";
import { homedir } from "os";

const USAGE_LOG = join(homedir(), ".scry", "usage.log");

export async function usageCommand(args: string[]) {
  if (!existsSync(USAGE_LOG)) {
    console.log("No usage data yet.");
    return;
  }

  const lines = readFileSync(USAGE_LOG, "utf-8").split("\n").filter(Boolean);
  if (lines.length === 0) {
    console.log("No usage data yet.");
    return;
  }

  // Parse: "2026-03-30T01:23:45.477Z  gsm list --view actions"
  const commands = new Map<string, number>();
  const byDay = new Map<string, number>();

  for (const line of lines) {
    const spaceIdx = line.indexOf("  ");
    if (spaceIdx < 0) continue;
    const ts = line.substring(0, spaceIdx);
    const rest = line.substring(spaceIdx + 2).trim();

    // Command = first 1-2 tokens (noun + verb)
    const parts = rest.split(/\s+/);
    const noun = parts[0] ?? "?";
    const verb = parts[1] && !parts[1].startsWith("-") ? parts[1] : "";
    const key = verb ? `${noun} ${verb}` : noun;
    commands.set(key, (commands.get(key) ?? 0) + 1);

    // Day
    const day = ts.substring(0, 10);
    byDay.set(day, (byDay.get(day) ?? 0) + 1);
  }

  // Command heatmap
  const sorted = [...commands.entries()].sort((a, b) => b[1] - a[1]);
  const max = sorted[0][1];
  const barWidth = 20;

  console.log(`${lines.length} invocations\n`);

  console.log("Commands:");
  for (const [cmd, count] of sorted) {
    const bar = "█".repeat(Math.max(1, Math.round((count / max) * barWidth)));
    console.log(`  ${String(count).padStart(4)}  ${bar}  ${cmd}`);
  }

  // Daily activity
  if (byDay.size > 1) {
    console.log("\nDaily:");
    const daySorted = [...byDay.entries()].sort();
    for (const [day, count] of daySorted) {
      const bar = "█".repeat(Math.max(1, Math.round((count / max) * barWidth)));
      console.log(`  ${day}  ${String(count).padStart(4)}  ${bar}`);
    }
  }
}
