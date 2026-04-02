import { describe, expect, it } from "bun:test";
import { mkdtempSync, rmSync, writeFileSync } from "fs";
import { join } from "path";
import { tmpdir } from "os";
import { formatSourceBadge, inferProvenance, loadLeylineSessions, parseSavedSourceFilter } from "./provenance";

describe("loadLeylineSessions", () => {
  it("loads valid jsonl records and skips junk", () => {
    const dir = mkdtempSync(join(tmpdir(), "scry-provenance-"));
    const file = join(dir, "leyline-sessions.jsonl");
    writeFileSync(file, [
      '{"ts":"2026-03-31T10:00:00Z","matchId":"m1","source":"leyline","eventName":"AIBotMatch"}',
      'not-json',
      '{"ts":"2026-03-31T10:01:00Z","matchId":"m2","source":"puzzle","eventName":"SparkyStarterDeckDuel","puzzleRef":"bolt-face"}',
    ].join("\n"));

    const sessions = loadLeylineSessions(file);
    expect(sessions).toHaveLength(2);
    expect(sessions[1]?.puzzleRef).toBe("bolt-face");

    rmSync(dir, { recursive: true, force: true });
  });
});

describe("inferProvenance", () => {
  it("returns explicit puzzle provenance from journal match id", () => {
    const provenance = inferProvenance("match-1", [
      {
        ts: "2026-03-31T10:01:00Z",
        matchId: "match-1",
        source: "puzzle",
        eventName: "SparkyStarterDeckDuel",
        puzzleRef: "bolt-face",
      },
    ]);

    expect(provenance.source).toBe("puzzle");
    expect(provenance.confidence).toBe("explicit");
    expect(provenance.puzzleRef).toBe("bolt-face");
  });

  it("falls back to inferred real when no session matches", () => {
    const provenance = inferProvenance("real-1", []);
    expect(provenance.source).toBe("real");
    expect(provenance.confidence).toBe("inferred");
    expect(provenance.matchId).toBe("real-1");
  });
});

describe("parseSavedSourceFilter", () => {
  it("defaults to real and unknown", () => {
    expect([...parseSavedSourceFilter([])]).toEqual(["real", "unknown"]);
  });

  it("parses explicit multi-source filters", () => {
    expect([...parseSavedSourceFilter(["--source", "puzzle,leyline"])]).toEqual(["puzzle", "leyline"]);
  });

  it("treats --all as any source", () => {
    expect(formatSourceBadge({ source: "puzzle", confidence: "explicit", matchId: "m1", puzzleRef: "bolt-face" })).toBe("puzzle:bolt-face");
    expect([...parseSavedSourceFilter(["--all"])]).toEqual(["real", "leyline", "puzzle", "unknown"]);
  });
});
