import { describe, it, expect } from "bun:test";
import { stripPrefix, zoneName, formatPhase } from "./format";

describe("stripPrefix", () => {
  it("strips matching prefix", () => {
    expect(stripPrefix("Phase_Main1", "Phase_")).toBe("Main1");
  });

  it("tries multiple prefixes", () => {
    expect(stripPrefix("ActionType_Cast", "Phase_", "ActionType_")).toBe("Cast");
  });

  it("returns unchanged if no prefix matches", () => {
    expect(stripPrefix("SomethingElse", "Phase_")).toBe("SomethingElse");
  });
});

describe("zoneName", () => {
  it("strips ZoneType_ prefix", () => {
    expect(zoneName("ZoneType_Battlefield")).toBe("Battlefield");
    expect(zoneName("ZoneType_Hand")).toBe("Hand");
    expect(zoneName("ZoneType_Graveyard")).toBe("Graveyard");
  });
});

describe("formatPhase", () => {
  it("phase only", () => {
    expect(formatPhase("Phase_Main1", "")).toBe("Main1");
  });

  it("phase + step", () => {
    expect(formatPhase("Phase_Combat", "Step_DeclareAttack")).toBe("Combat/DeclareAttack");
  });

  it("already stripped", () => {
    expect(formatPhase("Main1", "")).toBe("Main1");
  });
});
