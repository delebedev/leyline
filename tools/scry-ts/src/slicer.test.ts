import { describe, it, expect } from "bun:test";
import { sliceGames } from "./slicer";

function greHeader(ts: string, matchId: string) {
  return `[UnityCrossThreadLogger]${ts}: Match to ${matchId}: GreToClientEvent`;
}

function connectResp() {
  return `{"greToClientEvent":{"greToClientMessages":[{"type":"GREMessageType_ConnectResp"}]}}`;
}

function gsmLine() {
  return `{"greToClientEvent":{"greToClientMessages":[{"type":"GREMessageType_GameStateMessage","gameStateMessage":{"gameStateId":1}}]}}`;
}

function resultLine() {
  return `{"greToClientEvent":{"greToClientMessages":[{"type":"GREMessageType_GameStateMessage","gameStateMessage":{"gameStateId":99,"gameInfo":{"results":[{"result":"ResultType_WinLoss","winningTeamId":1}]}}}]}}`;
}

describe("sliceGames", () => {
  it("detects a single game", () => {
    const lines = [
      "some log noise",
      greHeader("01/01/2026 10:00:00", "abc"),
      connectResp(),
      gsmLine(),
      resultLine(),
    ];
    const slices = sliceGames(lines);
    expect(slices).toHaveLength(1);
    expect(slices[0].startLine).toBe(1);
    expect(slices[0].endLine).toBe(lines.length - 1);
    expect(slices[0].startTimestamp).toBe("01/01/2026 10:00:00");
    expect(slices[0].hasResult).toBe(true);
  });

  it("detects two games", () => {
    const lines = [
      greHeader("01/01/2026 10:00:00", "abc"),
      connectResp(),
      gsmLine(),
      resultLine(),
      greHeader("01/01/2026 10:30:00", "def"),
      connectResp(),
      gsmLine(),
    ];
    const slices = sliceGames(lines);
    expect(slices).toHaveLength(2);
    expect(slices[0].startLine).toBe(0);
    expect(slices[0].endLine).toBe(3);
    expect(slices[1].startLine).toBe(4);
    expect(slices[1].startTimestamp).toBe("01/01/2026 10:30:00");
    expect(slices[1].hasResult).toBe(false);
  });

  it("returns empty for no games", () => {
    const lines = ["noise", "more noise", "[UnityCrossThreadLogger]Client.SceneChange {}"];
    expect(sliceGames(lines)).toHaveLength(0);
  });

  it("skips GRE headers without ConnectResp", () => {
    const lines = [
      greHeader("01/01/2026 10:00:00", "abc"),
      gsmLine(), // GSM, not ConnectResp
      greHeader("01/01/2026 10:30:00", "def"),
      connectResp(),
    ];
    const slices = sliceGames(lines);
    expect(slices).toHaveLength(1);
    expect(slices[0].startTimestamp).toBe("01/01/2026 10:30:00");
  });
});
