import { loadEvents } from "../log";

export async function eventsCommand(args: string[]) {
  const logPath = args.find((a) => !a.startsWith("-"));
  const events = await loadEvents(logPath);

  let greCount = 0;
  let greGames = new Set<string>();
  const fdRequests = new Map<string, number>();
  let sceneCount = 0;

  for (const event of events) {
    switch (event.type) {
      case "gre":
        greCount++;
        if (event.matchId) greGames.add(event.matchId);
        break;
      case "fd-request":
        fdRequests.set(event.name, (fdRequests.get(event.name) ?? 0) + 1);
        break;
      case "scene":
        sceneCount++;
        break;
    }
  }

  // FD summary
  const fdTotal = [...fdRequests.values()].reduce((a, b) => a + b, 0);
  if (fdTotal > 0) {
    const top = [...fdRequests.entries()].sort((a, b) => b[1] - a[1]);
    const topStr = top.slice(0, 5).map(([name, count]) => `${name}: ${count}`).join(", ");
    console.log(`${fdTotal} FD pairs (${topStr}${top.length > 5 ? ", ..." : ""})`);
  }

  // GRE summary
  if (greCount > 0) {
    console.log(`${greCount} GRE messages (${greGames.size} game${greGames.size !== 1 ? "s" : ""})`);
  }

  // Scene summary
  if (sceneCount > 0) {
    console.log(`${sceneCount} scene changes`);
  }

  if (fdTotal === 0 && greCount === 0 && sceneCount === 0) {
    console.log("No events found in Player.log");
  }
}
