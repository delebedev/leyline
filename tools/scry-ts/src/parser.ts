/**
 * Player.log line parser.
 *
 * Extracts structured events from Arena's Player.log:
 * - GRE messages (game state, actions, connect/disconnect)
 * - FD request/response pairs (lobby, decks, events)
 * - Scene changes (lobby navigation)
 */

export type LogEvent =
  | { type: "gre"; timestamp: string | null; matchId: string | null; messages: unknown[] }
  | { type: "fd-request"; name: string; id: string; request: string }
  | { type: "fd-response"; name: string; id: string; payload: unknown }
  | { type: "scene"; from: string; to: string; initiator: string; context: string };

const GRE_HEADER = /\[UnityCrossThreadLogger\](\d{2}\/\d{2}\/\d{4} \d{2}:\d{2}:\d{2}): Match to ([0-9a-zA-Z_-]+): GreToClientEvent/;
const STANDALONE_GRE = /^\{\s*"greToClientEvent":/;
const FD_REQUEST = /\[UnityCrossThreadLogger\]==> (\S+) (\{.+\})$/;
const FD_RESPONSE = /^<== (\S+)\(([^)]+)\)/;
const SCENE_CHANGE = /\[UnityCrossThreadLogger\]Client\.SceneChange\s+(\{.+\})/;

export function* parseLog(lines: Iterable<string>): Generator<LogEvent> {
  const it = lines[Symbol.iterator]();

  for (let result = it.next(); !result.done; result = it.next()) {
    const line = result.value;

    // GRE with header
    const greMatch = GRE_HEADER.exec(line);
    if (greMatch) {
      const next = it.next();
      if (next.done) break;
      try {
        const data = JSON.parse(next.value);
        const messages = data?.greToClientEvent?.greToClientMessages ?? [];
        yield { type: "gre", timestamp: greMatch[1], matchId: greMatch[2], messages };
      } catch {}
      continue;
    }

    // Standalone GRE JSON
    if (STANDALONE_GRE.test(line)) {
      try {
        const data = JSON.parse(line);
        const messages = data?.greToClientEvent?.greToClientMessages ?? [];
        yield { type: "gre", timestamp: null, matchId: null, messages };
      } catch {}
      continue;
    }

    // FD request
    const fdReq = FD_REQUEST.exec(line);
    if (fdReq) {
      try {
        const payload = JSON.parse(fdReq[2]);
        yield { type: "fd-request", name: fdReq[1], id: payload.id ?? "", request: payload.request ?? "" };
      } catch {}
      continue;
    }

    // FD response
    const fdResp = FD_RESPONSE.exec(line);
    if (fdResp) {
      const next = it.next();
      if (next.done) break;
      try {
        const payload = JSON.parse(next.value);
        yield { type: "fd-response", name: fdResp[1], id: fdResp[2], payload };
      } catch {}
      continue;
    }

    // Scene change
    const sceneMatch = SCENE_CHANGE.exec(line);
    if (sceneMatch) {
      try {
        const raw = JSON.parse(sceneMatch[1]);
        yield {
          type: "scene",
          from: raw.fromSceneName ?? "",
          to: raw.toSceneName ?? "",
          initiator: raw.initiator ?? "",
          context: raw.context ?? "",
        };
      } catch {}
      continue;
    }
  }
}
