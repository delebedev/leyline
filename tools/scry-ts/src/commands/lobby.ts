import { loadEvents } from "../log";
import type { LogEvent } from "../parser";

interface FdPair {
  name: string;
  id: string;
  request: string;
  response: unknown | null;
}

/** Match FD requests to their responses by id. */
function pairFdEvents(events: LogEvent[]): FdPair[] {
  const pending = new Map<string, FdPair>();
  const pairs: FdPair[] = [];

  for (const event of events) {
    if (event.type === "fd-request") {
      const pair: FdPair = { name: event.name, id: event.id, request: event.request, response: null };
      pending.set(event.id, pair);
      pairs.push(pair);
    } else if (event.type === "fd-response") {
      const pair = pending.get(event.id);
      if (pair) {
        pair.response = event.payload;
        pending.delete(event.id);
      }
    }
  }

  return pairs;
}

export async function lobbyCommand(args: string[]) {
  const verb = args[0];

  if (!verb || verb === "--help" || verb === "-h") {
    console.log("Usage: scry lobby <command>\n");
    console.log("Commands:");
    console.log("  list             List all lobby request/response pairs");
    console.log("  show <id>        Show full request + response by transaction id");
    console.log("  search <term>    Search payloads for a string");
    console.log("\nFlags:");
    console.log("  --json           Raw JSON output (lobby show only)");
    return;
  }

  if (verb === "list") {
    await lobbyList();
  } else if (verb === "show") {
    await lobbyShow(args.slice(1));
  } else if (verb === "search") {
    await lobbySearch(args.slice(1));
  } else {
    console.error(`Unknown lobby command: ${verb}\nRun 'scry lobby --help' for usage.`);
    process.exit(1);
  }
}

async function lobbyList() {
  const events = await loadEvents();
  const pairs = pairFdEvents(events);

  if (pairs.length === 0) {
    console.log("No lobby requests found.");
    return;
  }

  // Summary by type
  const counts = new Map<string, number>();
  for (const p of pairs) {
    counts.set(p.name, (counts.get(p.name) ?? 0) + 1);
  }

  console.log(`${"#".padStart(3)}  ${"Name".padEnd(35)}  ${"Id".padEnd(10)}  Response`);
  console.log("—".repeat(72));

  for (let i = 0; i < pairs.length; i++) {
    const p = pairs[i];
    const hasResp = p.response != null;
    const respSize = hasResp ? `${JSON.stringify(p.response).length}B` : "—";
    const shortId = p.id.substring(0, 8);
    console.log(
      `${String(i + 1).padStart(3)}  ${p.name.padEnd(35)}  ${shortId.padEnd(10)}  ${respSize}`
    );
  }

  console.log("");
  const sorted = [...counts.entries()].sort((a, b) => b[1] - a[1]);
  console.log(`${pairs.length} requests (${sorted.map(([n, c]) => `${n}: ${c}`).join(", ")})`);
}

async function lobbyShow(args: string[]) {
  const target = args.find((a) => !a.startsWith("-"));
  if (!target) {
    console.error("Usage: scry lobby show <id-or-index-or-name>");
    process.exit(1);
  }
  const jsonMode = args.includes("--json");

  const events = await loadEvents();
  const pairs = pairFdEvents(events);

  // Find by: numeric index, id prefix, or command name
  let found: FdPair | undefined;
  const asNum = parseInt(target, 10);
  if (!isNaN(asNum) && asNum >= 1 && asNum <= pairs.length) {
    found = pairs[asNum - 1];
  }
  if (!found) {
    found = pairs.find((p) => p.id.startsWith(target));
  }
  if (!found) {
    // Match by name (last occurrence — most likely to have interesting response)
    const byName = pairs.filter((p) => p.name.toLowerCase().includes(target.toLowerCase()));
    found = byName[byName.length - 1];
  }

  if (!found) {
    console.error(`Not found: ${target}`);
    process.exit(1);
  }

  if (jsonMode) {
    console.log(JSON.stringify({ request: found.request, response: found.response }, null, 2));
    return;
  }

  console.log(`${found.name}  id=${found.id}`);
  console.log("");

  console.log("Request:");
  try {
    const req = typeof found.request === "string" ? JSON.parse(found.request) : found.request;
    console.log(JSON.stringify(req, null, 2));
  } catch {
    console.log(`  ${found.request || "(empty)"}`);
  }
  console.log("");

  console.log("Response:");
  if (found.response != null) {
    console.log(JSON.stringify(found.response, null, 2));
  } else {
    console.log("  (no response)");
  }
}

async function lobbySearch(args: string[]) {
  const term = args.find((a) => !a.startsWith("-"));
  if (!term) {
    console.error("Usage: scry lobby search <term>");
    process.exit(1);
  }
  const termLower = term.toLowerCase();

  const events = await loadEvents();
  const pairs = pairFdEvents(events);

  let hits = 0;
  for (let i = 0; i < pairs.length; i++) {
    const p = pairs[i];
    const reqStr = typeof p.request === "string" ? p.request : JSON.stringify(p.request);
    const respStr = p.response != null ? JSON.stringify(p.response) : "";
    const nameMatch = p.name.toLowerCase().includes(termLower);
    const reqMatch = reqStr.toLowerCase().includes(termLower);
    const respMatch = respStr.toLowerCase().includes(termLower);

    if (nameMatch || reqMatch || respMatch) {
      const where = [nameMatch && "name", reqMatch && "request", respMatch && "response"].filter(Boolean).join(", ");
      // Find context snippet
      const haystack = respMatch ? respStr : reqStr;
      const idx = haystack.toLowerCase().indexOf(termLower);
      const start = Math.max(0, idx - 30);
      const end = Math.min(haystack.length, idx + term.length + 50);
      const snippet = (start > 0 ? "..." : "") + haystack.substring(start, end) + (end < haystack.length ? "..." : "");

      console.log(`${String(i + 1).padStart(3)}  ${p.name.padEnd(35)}  [${where}]`);
      console.log(`     ${snippet}`);
      hits++;
    }
  }

  if (hits === 0) {
    console.log(`No matches for "${term}"`);
  } else {
    console.log(`\n${hits} matches`);
  }
}
