import { describe, it, expect } from "bun:test";
import { parseLog, type LogEvent } from "./parser";

function collect(lines: string[]): LogEvent[] {
  return [...parseLog(lines)];
}

describe("parseLog", () => {
  it("extracts GRE block with header", () => {
    const lines = [
      '[UnityCrossThreadLogger]29/03/2026 14:30:00: Match to abc123: GreToClientEvent',
      '{"greToClientEvent":{"greToClientMessages":[{"type":"GREMessageType_GameStateMessage"}]}}',
    ];
    const events = collect(lines);
    expect(events).toHaveLength(1);
    expect(events[0].type).toBe("gre");
    if (events[0].type === "gre") {
      expect(events[0].timestamp).toBe("29/03/2026 14:30:00");
      expect(events[0].matchId).toBe("abc123");
      expect(events[0].messages).toHaveLength(1);
    }
  });

  it("extracts standalone GRE JSON", () => {
    const lines = [
      '{"greToClientEvent":{"greToClientMessages":[{"type":"GREMessageType_ConnectResp"}]}}',
    ];
    const events = collect(lines);
    expect(events).toHaveLength(1);
    expect(events[0].type).toBe("gre");
  });

  it("extracts FD request/response pair", () => {
    const lines = [
      '[UnityCrossThreadLogger]==> GraphGetGraphState {"id":"abc-123","request":"{\\"GraphId\\":\\"NPE\\"}"}',
      '<== GraphGetGraphState(abc-123)',
      '{"NodeStates":{"PlayFamiliar1":{"Status":"Completed"}}}',
    ];
    const events = collect(lines);
    expect(events).toHaveLength(2);
    expect(events[0].type).toBe("fd-request");
    if (events[0].type === "fd-request") {
      expect(events[0].name).toBe("GraphGetGraphState");
      expect(events[0].id).toBe("abc-123");
    }
    expect(events[1].type).toBe("fd-response");
    if (events[1].type === "fd-response") {
      expect(events[1].name).toBe("GraphGetGraphState");
      expect((events[1].payload as any).NodeStates).toBeDefined();
    }
  });

  it("extracts scene change", () => {
    const lines = [
      '[UnityCrossThreadLogger]Client.SceneChange {"fromSceneName":"Home","toSceneName":"DeckBuilder","initiator":"User","context":""}',
    ];
    const events = collect(lines);
    expect(events).toHaveLength(1);
    expect(events[0].type).toBe("scene");
    if (events[0].type === "scene") {
      expect(events[0].from).toBe("Home");
      expect(events[0].to).toBe("DeckBuilder");
    }
  });

  it("skips unrecognized lines", () => {
    const lines = [
      "some random log line",
      "another one",
      '[UnityCrossThreadLogger]Client.SceneChange {"fromSceneName":"A","toSceneName":"B","initiator":"System","context":""}',
      "more noise",
    ];
    const events = collect(lines);
    expect(events).toHaveLength(1);
    expect(events[0].type).toBe("scene");
  });

  it("handles malformed JSON gracefully", () => {
    const lines = [
      '[UnityCrossThreadLogger]29/03/2026 14:30:00: Match to abc: GreToClientEvent',
      'not valid json {{{',
      '[UnityCrossThreadLogger]Client.SceneChange {"fromSceneName":"A","toSceneName":"B","initiator":"System","context":""}',
    ];
    const events = collect(lines);
    expect(events).toHaveLength(1);
    expect(events[0].type).toBe("scene");
  });
});
