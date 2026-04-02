import { describe, expect, it } from "bun:test";
import { normalizeGameShowArgs } from "./game";

describe("normalizeGameShowArgs", () => {
  it("preserves default live-last behavior", () => {
    expect(normalizeGameShowArgs([])).toEqual([]);
    expect(normalizeGameShowArgs(["last"])).toEqual(["last"]);
  });

  it("keeps explicit --game references intact", () => {
    expect(normalizeGameShowArgs(["--game", "2026-04-01_21-55-59"])).toEqual(["--game", "2026-04-01_21-55-59"]);
  });

  it("rewrites positional saved refs into --game lookups", () => {
    expect(normalizeGameShowArgs(["2026-04-01_21-55-59"])).toEqual(["--game", "2026-04-01_21-55-59"]);
  });

  it("rewrites positional live indexes into --game lookups", () => {
    expect(normalizeGameShowArgs(["3"])).toEqual(["--game", "3"]);
  });
});
