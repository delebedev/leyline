import { describe, it, expect } from "bun:test";
import { filenameFromTimestamp, isAlreadySaved, type Catalog } from "./catalog";

describe("filenameFromTimestamp", () => {
  it("converts Arena timestamp to filename", () => {
    expect(filenameFromTimestamp("29/03/2026 16:10:08")).toBe("2026-03-29_16-10-08.log");
  });

  it("handles null timestamp", () => {
    const name = filenameFromTimestamp(null);
    expect(name).toMatch(/^unknown_\d+\.log$/);
  });

  it("handles malformed timestamp", () => {
    const name = filenameFromTimestamp("not a timestamp");
    expect(name).toMatch(/^unknown_\d+\.log$/);
  });
});

describe("isAlreadySaved", () => {
  const catalog: Catalog = {
    version: 1,
    games: [
      {
        file: "2026-03-29_16-10-08.log",
        matchId: "abc-123",
        startTimestamp: "29/03/2026 16:10:08",
        result: "win",
        turns: 20,
        gsmCount: 500,
        savedAt: "2026-03-29T18:00:00Z",
        notes: "",
      },
    ],
  };

  it("returns true for matching matchId + timestamp", () => {
    expect(isAlreadySaved(catalog, "abc-123", "29/03/2026 16:10:08")).toBe(true);
  });

  it("returns false for different matchId", () => {
    expect(isAlreadySaved(catalog, "def-456", "29/03/2026 16:10:08")).toBe(false);
  });

  it("returns false for different timestamp", () => {
    expect(isAlreadySaved(catalog, "abc-123", "30/03/2026 10:00:00")).toBe(false);
  });

  it("returns false for empty catalog", () => {
    expect(isAlreadySaved({ version: 1, games: [] }, "abc", "ts")).toBe(false);
  });
});
