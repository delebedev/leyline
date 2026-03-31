import { describe, it, expect } from "bun:test";
import { detectGames, type Game } from "./games";
import type { LogEvent } from "./parser";

function gre(messages: any[], opts?: { timestamp?: string; matchId?: string }): LogEvent {
  return {
    type: "gre",
    timestamp: opts?.timestamp ?? "29/03/2026 14:30:00",
    matchId: opts?.matchId ?? "match-1",
    messages,
  };
}

function connectResp(seat: number = 1) {
  return { type: "GREMessageType_ConnectResp", systemSeatIds: [seat] };
}

function gsm(gsId: number, opts?: { type?: string; turn?: number; phase?: string; annotations?: any[]; gameInfo?: any }) {
  return {
    type: "GREMessageType_GameStateMessage",
    gameStateMessage: {
      gameStateId: gsId,
      type: `GameStateType_${opts?.type ?? "Diff"}`,
      turnInfo: {
        turnNumber: opts?.turn ?? 1,
        phase: `Phase_${opts?.phase ?? "Main1"}`,
        step: "",
        activePlayer: 1,
      },
      annotations: opts?.annotations ?? [],
      gameObjects: [],
      gameInfo: opts?.gameInfo,
    },
  };
}

function gameOver(winner: number) {
  return { results: [{ result: "ResultType_WinLoss", winningTeamId: winner }] };
}

describe("detectGames", () => {
  it("detects a single game", () => {
    const events = [
      gre([connectResp()]),
      gre([gsm(1, { type: "Full" })]),
      gre([gsm(2, { turn: 1, phase: "Main1" })]),
      gre([gsm(3, { turn: 2, gameInfo: gameOver(1) })]),
    ];
    const games = detectGames(events);
    expect(games).toHaveLength(1);
    expect(games[0].index).toBe(1);
    expect(games[0].result).toBe("win");
    expect(games[0].active).toBe(false);
    expect(games[0].greMessages).toHaveLength(3);
  });

  it("detects two games", () => {
    const events = [
      gre([connectResp()], { matchId: "m1" }),
      gre([gsm(1)]),
      gre([gsm(2, { gameInfo: gameOver(2) })]),
      gre([connectResp()], { matchId: "m2" }),
      gre([gsm(1, { type: "Full" })]),
    ];
    const games = detectGames(events);
    expect(games).toHaveLength(2);
    expect(games[0].result).toBe("loss");
    expect(games[0].active).toBe(false);
    expect(games[1].active).toBe(true);
    expect(games[1].result).toBeNull();
  });

  it("marks last game as active if no result", () => {
    const events = [
      gre([connectResp()]),
      gre([gsm(1, { type: "Full" })]),
      gre([gsm(2)]),
    ];
    const games = detectGames(events);
    expect(games).toHaveLength(1);
    expect(games[0].active).toBe(true);
  });

  it("skips GRE events before first ConnectResp", () => {
    const events = [
      gre([gsm(99)]), // orphan — no game yet
      gre([connectResp()]),
      gre([gsm(1, { type: "Full" })]),
    ];
    const games = detectGames(events);
    expect(games).toHaveLength(1);
    expect(games[0].greMessages).toHaveLength(1);
  });

  it("detects seat 2 and win/loss correctly", () => {
    const events = [
      gre([connectResp(2)]), // we are seat 2
      gre([gsm(1, { type: "Full" })]),
      gre([gsm(2, { gameInfo: gameOver(2) })]), // seat 2 wins = we win
    ];
    const games = detectGames(events);
    expect(games).toHaveLength(1);
    expect(games[0].ourSeat).toBe(2);
    expect(games[0].result).toBe("win");
  });

  it("detects loss when opponent wins and we are seat 2", () => {
    const events = [
      gre([connectResp(2)]),
      gre([gsm(1, { type: "Full" })]),
      gre([gsm(2, { gameInfo: gameOver(1) })]), // seat 1 wins = we lose
    ];
    const games = detectGames(events);
    expect(games[0].ourSeat).toBe(2);
    expect(games[0].result).toBe("loss");
  });

  it("returns empty for no GRE events", () => {
    const events: LogEvent[] = [
      { type: "scene", from: "Home", to: "Play", initiator: "User", context: "" },
    ];
    expect(detectGames(events)).toHaveLength(0);
  });
});
