/**
 * Game boundary detection from Player.log events.
 *
 * A "game" starts at ConnectResp and ends at game-over (results in gameInfo)
 * or the next ConnectResp. Yields game slices with their GRE events.
 */

import { type LogEvent, parseLog } from "./parser";

export interface Game {
  index: number;
  matchId: string | null;
  /** Our seat from ConnectResp systemSeatIds. Usually 1, but 2 in Brawl/PvP. */
  ourSeat: number;
  startTimestamp: string | null;
  endTimestamp: string | null;
  greMessages: GsmSummary[];
  result: "win" | "loss" | "draw" | null;
  active: boolean;
}

export interface GsmSummary {
  gsId: number;
  type: string; // "Full" | "Diff"
  turn: number;
  phase: string;
  step: string;
  activePlayer: number;
  annotationCount: number;
  annotationTypes: string[];
  objectCount: number;
  timestamp: string | null;
  raw: any; // original gameStateMessage for drill-down
}

function extractGsm(msg: any, timestamp: string | null): GsmSummary | null {
  const gsm = msg.gameStateMessage;
  if (!gsm) return null;

  const ti = gsm.turnInfo ?? {};
  const annotations = gsm.annotations ?? [];
  const persistent = gsm.persistentAnnotations ?? [];
  const types = new Set<string>();
  for (const ann of [...annotations, ...persistent]) {
    for (const t of ann.type ?? []) {
      types.add(t.replace("AnnotationType_", ""));
    }
  }

  return {
    gsId: gsm.gameStateId ?? 0,
    type: (gsm.type ?? "").replace("GameStateType_", ""),
    turn: ti.turnNumber ?? 0,
    phase: (ti.phase ?? "").replace("Phase_", ""),
    step: (ti.step ?? "").replace("Step_", ""),
    activePlayer: ti.activePlayer ?? 0,
    annotationCount: annotations.length,
    annotationTypes: [...types],
    objectCount: (gsm.gameObjects ?? []).length,
    timestamp,
    raw: gsm,
  };
}

function extractResult(msg: any, ourSeat: number): "win" | "loss" | "draw" | null {
  const gi = msg.gameStateMessage?.gameInfo;
  if (!gi) return null;

  // results can be at gameInfo.results or gameInfo.matchState.resultList
  const results = gi.results ?? gi.matchState?.resultList ?? [];
  if (results.length === 0) return null;

  for (const r of results) {
    // field is "result" not "resultType" in Player.log JSON
    const type = r.result ?? r.resultType ?? "";
    if (type.includes("WinLoss")) {
      const winner = r.winningTeamId ?? r.winningSeatId;
      if (winner === ourSeat) return "win";
      if (winner !== ourSeat) return "loss";
    }
    if (type.includes("Draw")) return "draw";
  }
  return null;
}

export function detectGames(events: Iterable<LogEvent>): Game[] {
  const games: Game[] = [];
  let current: Game | null = null;

  for (const event of events) {
    if (event.type !== "gre") continue;

    const isConnect = event.messages.some(
      (m: any) => m.type === "GREMessageType_ConnectResp"
    );

    if (isConnect) {
      // Close previous game
      if (current) {
        current.active = false;
        games.push(current);
      }
      // Extract our seat from ConnectResp.systemSeatIds
      const connectMsg = event.messages.find(
        (m: any) => m.type === "GREMessageType_ConnectResp"
      ) as any;
      const ourSeat = connectMsg?.systemSeatIds?.[0] ?? 1;

      current = {
        index: games.length + 1,
        matchId: null, // set from gameInfo.matchID when first GSM arrives
        ourSeat,
        startTimestamp: event.timestamp,
        endTimestamp: null,
        greMessages: [],
        result: null,
        active: true,
      };
    }

    if (!current) continue;

    current.endTimestamp = event.timestamp;

    for (const msg of event.messages) {
      const gsm = extractGsm(msg, event.timestamp);
      if (gsm) current.greMessages.push(gsm);

      // Prefer gameInfo.matchID (per-game) over header matchId (connection/session)
      const gameMatchId = msg.gameStateMessage?.gameInfo?.matchID;
      if (gameMatchId) {
        current.matchId = gameMatchId;
      }

      const result = extractResult(msg, current.ourSeat);
      if (result) {
        current.result = result;
        current.active = false;
      }
    }
  }

  // Push last game (may still be active)
  if (current) games.push(current);

  return games;
}
