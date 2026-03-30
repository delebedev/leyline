import { resolveGame } from "../resolve";
import { stripPrefix } from "../format";
import { getResolver } from "../cards";

/** GRE message types that represent player interactions (not GSMs). */
const PROMPT_TYPES = new Set([
  "GREMessageType_ActionsAvailableReq",
  "GREMessageType_SelectTargetsReq",
  "GREMessageType_SelectNReq",
  "GREMessageType_DeclareAttackersReq",
  "GREMessageType_DeclareBlockersReq",
  "GREMessageType_OptionalActionMessage",
  "GREMessageType_SearchReq",
  "GREMessageType_PromptReq",
  "GREMessageType_MulliganReq",
  "GREMessageType_ChooseStartingPlayerReq",
  "GREMessageType_GroupReq",
  "GREMessageType_OrderReq",
  "GREMessageType_OrderDamageConfirmation",
  "GREMessageType_SelectReplacementReq",
  "GREMessageType_IntermissionReq",
]);

interface PromptEntry {
  gsId: number;
  type: string;
  summary: string;
  raw: any;
}

export async function promptsCommand(args: string[]) {
  if (args[0] === "--help" || args[0] === "-h") {
    console.log("Usage: scry prompts [flags]\n");
    console.log("List all player interaction prompts in a game —\n" +
                "the dialogs, choices, and requests the server sends.\n");
    console.log("Flags:");
    console.log("  --game REF    Game reference");
    console.log("  --type TYPE   Filter by prompt type (e.g. SelectNReq, OptionalAction)");
    console.log("  --json        Raw JSON output");
    return;
  }

  const { game, label } = await resolveGame(args);
  const resolver = getResolver();

  let typeFilter: string | null = null;
  for (let i = 0; i < args.length; i++) {
    if (args[i] === "--type" && i + 1 < args.length) {
      typeFilter = args[++i];
    }
  }
  const jsonMode = args.includes("--json");

  // Scan all GRE messages in the game (not just GSMs)
  const prompts: PromptEntry[] = [];
  for (const gsm of game.greMessages) {
    // gsm.raw is the gameStateMessage — but we need the parent GRE block
    // which has all message types. The raw is stored on individual GSMs only.
    // We need to go back to the raw GRE messages from the parser events.
  }

  // Replay from raw events — the game's GRE blocks have all messages
  // We need to access the raw parsed events. Since resolveGame gives us
  // Game with greMessages (GSMs only), we re-parse for the full GRE blocks.
  const { parseLog } = await import("../parser");
  const { readSavedGame, loadCatalog } = await import("../catalog");
  const { loadEvents } = await import("../log");
  const { detectGames } = await import("../games");

  // Get raw events for this game
  let allEvents: any[];
  const catalog = loadCatalog();
  const gameRef = args.find((a, i) => i > 0 && args[i - 1] === "--game") ?? null;
  const catalogEntry = gameRef
    ? catalog.games.find((g) => g.file.includes(gameRef))
    : null;

  if (catalogEntry) {
    const lines = readSavedGame(catalogEntry.file);
    allEvents = [...parseLog(lines)];
  } else {
    allEvents = await loadEvents();
  }

  // Find GRE events for our game (by timestamp match)
  let inGame = false;
  for (const event of allEvents) {
    if (event.type !== "gre") continue;

    // Detect game boundaries
    const isConnect = event.messages.some(
      (m: any) => m.type === "GREMessageType_ConnectResp"
    );
    if (isConnect) {
      // Check if this is our game by timestamp
      if (event.timestamp === game.startTimestamp) {
        inGame = true;
      } else if (inGame) {
        break; // next game started
      }
    }

    if (!inGame) continue;

    for (const msg of event.messages) {
      const msgType: string = msg.type ?? "";
      if (!PROMPT_TYPES.has(msgType)) continue;

      const shortType = stripPrefix(msgType, "GREMessageType_");
      if (typeFilter && !shortType.toLowerCase().includes(typeFilter.toLowerCase())) continue;

      const gsId = msg.gameStateId ?? 0;
      const summary = summarizePrompt(msg, shortType, resolver);

      prompts.push({ gsId, type: shortType, summary, raw: msg });
    }
  }

  if (prompts.length === 0) {
    console.log("No prompts found.");
    return;
  }

  if (jsonMode) {
    console.log(JSON.stringify(prompts.map((p) => ({ gsId: p.gsId, type: p.type, message: p.raw })), null, 2));
    return;
  }

  // Summary by type
  const counts = new Map<string, number>();
  for (const p of prompts) {
    counts.set(p.type, (counts.get(p.type) ?? 0) + 1);
  }

  console.log(`Prompts (${label}):\n`);

  const sorted = [...counts.entries()].sort((a, b) => b[1] - a[1]);
  for (const [type, count] of sorted) {
    console.log(`  ${String(count).padStart(3)}x  ${type}`);
  }

  console.log(`\n${prompts.length} total\n`);

  // Detail: list each prompt with summary
  console.log("Detail:");
  for (const p of prompts) {
    console.log(`  gs=${p.gsId}  ${p.type.padEnd(25)}  ${p.summary}`);
  }
}

function summarizePrompt(msg: any, type: string, resolver: any): string {
  switch (type) {
    case "ActionsAvailableReq": {
      const actions = msg.actionsAvailableReq?.actions ?? msg.actions ?? [];
      const types = new Map<string, number>();
      for (const a of actions) {
        const at = stripPrefix((a.action ?? a).actionType ?? "", "ActionType_");
        types.set(at, (types.get(at) ?? 0) + 1);
      }
      return [...types.entries()].map(([t, c]) => `${t}:${c}`).join(" ");
    }
    case "SelectTargetsReq": {
      const req = msg.selectTargetsReq ?? msg;
      const targets = req.targets ?? [];
      return `${targets.length} target specs`;
    }
    case "SelectNReq": {
      const req = msg.selectNReq ?? msg;
      const n = req.maxSel ?? req.minSel ?? "?";
      const prompt = req.prompt?.promptId ?? "";
      return `select ${n}${prompt ? ` (${prompt})` : ""}`;
    }
    case "DeclareAttackersReq": {
      const req = msg.declareAttackersReq ?? msg;
      const count = req.attackers?.length ?? 0;
      return `${count} eligible`;
    }
    case "DeclareBlockersReq": {
      const req = msg.declareBlockersReq ?? msg;
      const count = req.blockers?.length ?? 0;
      return `${count} eligible`;
    }
    case "OptionalActionMessage": {
      const req = msg.optionalActionMessage ?? msg;
      return req.prompt?.promptId ?? "may trigger";
    }
    case "SearchReq": {
      return "search library";
    }
    case "MulliganReq": {
      return "mulligan decision";
    }
    case "IntermissionReq": {
      return "game over";
    }
    default:
      return "";
  }
}
