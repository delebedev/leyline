import { describe, expect, it } from "bun:test";
import {
  buildAnnotationProfile,
  buildPromptProfile,
  buildActionProfile,
  type AnnotationInstance,
  type PromptInstance,
  type ActionInstance,
} from "./catalog-builder";

describe("buildAnnotationProfile", () => {
  it("splits always/sometimes keys and computes persistence + value samples", () => {
    const instances: AnnotationInstance[] = [
      {
        type: "DamageDealt",
        keys: ["damage_amount", "source", "is_combat"],
        values: { damage_amount: 3, source: "Lightning Bolt", is_combat: 0 },
        isPersistent: false,
        gameLabel: "2026-03-29_16-10-08",
        gsmId: 10,
        affectorId: 100,
        affectedIds: [200],
        coTypes: ["ObjectIdChanged", "ZoneTransfer"],
      },
      {
        type: "DamageDealt",
        keys: ["damage_amount", "source"],
        values: { damage_amount: 5, source: "Shock" },
        isPersistent: true,
        gameLabel: "2026-03-30_12-00-00",
        gsmId: 20,
        affectorId: 101,
        affectedIds: [201],
        coTypes: ["ObjectIdChanged"],
      },
    ];

    const profile = buildAnnotationProfile("DamageDealt", instances);

    expect(profile.type).toBe("DamageDealt");
    expect(profile.instances).toBe(2);
    expect(profile.games).toBe(2);

    // damage_amount and source appear in both → always
    expect(profile.alwaysKeys).toContain("damage_amount");
    expect(profile.alwaysKeys).toContain("source");
    // is_combat appears in 1 of 2 → sometimes at 50%
    expect(profile.sometimesKeys).toEqual([{ key: "is_combat", pct: 50 }]);

    // Value samples
    expect(profile.valueSamples["damage_amount"]?.has("3")).toBe(true);
    expect(profile.valueSamples["damage_amount"]?.has("5")).toBe(true);

    // Persistence: one transient, one persistent → mixed
    expect(profile.persistence).toBe("mixed");

    // Co-types: ObjectIdChanged appears in both GSMs (2/2 = 100% > 50%)
    expect(profile.coTypes.has("ObjectIdChanged")).toBe(true);
    // ZoneTransfer only in 1 of 2 GSMs = 50%, not > 50%
    expect(profile.coTypes.has("ZoneTransfer")).toBe(false);

    // Card count from unique affectorIds
    expect(profile.cards.count).toBe(2);

    // Date range
    expect(profile.firstSeen).toBe("2026-03-29_16-10-08");
    expect(profile.lastSeen).toBe("2026-03-30_12-00-00");
  });

  it("classifies all-persistent correctly", () => {
    const instances: AnnotationInstance[] = [
      {
        type: "TargetSpec",
        keys: ["targetId"],
        values: { targetId: 42 },
        isPersistent: true,
        gameLabel: "game1",
        gsmId: 1,
        affectorId: undefined,
        affectedIds: [],
        coTypes: [],
      },
    ];

    const profile = buildAnnotationProfile("TargetSpec", instances);
    expect(profile.persistence).toBe("persistent");
    expect(profile.alwaysKeys).toEqual(["targetId"]);
    expect(profile.sometimesKeys).toEqual([]);
  });
});

describe("buildPromptProfile", () => {
  it("splits always/sometimes fields across instances", () => {
    const instances: PromptInstance[] = [
      {
        type: "SelectTargetsReq",
        gameLabel: "2026-03-29_16-10-08",
        fields: ["prompt", "targets", "minTargets", "maxTargets"],
        raw: {},
      },
      {
        type: "SelectTargetsReq",
        gameLabel: "2026-03-30_12-00-00",
        fields: ["prompt", "targets", "allowCancel"],
        raw: {},
      },
    ];

    const profile = buildPromptProfile("SelectTargetsReq", instances);

    expect(profile.type).toBe("SelectTargetsReq");
    expect(profile.occurrences).toBe(2);
    expect(profile.games).toBe(2);

    // prompt + targets appear in both → always
    expect(profile.alwaysFields).toContain("prompt");
    expect(profile.alwaysFields).toContain("targets");

    // minTargets, maxTargets, allowCancel → sometimes (50% each)
    const sometimesNames = profile.sometimesFields.map((f) => f.field);
    expect(sometimesNames).toContain("minTargets");
    expect(sometimesNames).toContain("maxTargets");
    expect(sometimesNames).toContain("allowCancel");

    for (const f of profile.sometimesFields) {
      expect(f.pct).toBe(50);
    }

    expect(profile.firstSeen).toBe("2026-03-29_16-10-08");
    expect(profile.lastSeen).toBe("2026-03-30_12-00-00");
  });

  it("handles single instance — all fields are always", () => {
    const instances: PromptInstance[] = [
      {
        type: "MulliganReq",
        gameLabel: "game1",
        fields: ["prompt", "mulliganCount"],
        raw: {},
      },
    ];

    const profile = buildPromptProfile("MulliganReq", instances);
    expect(profile.alwaysFields).toEqual(["prompt", "mulliganCount"]);
    expect(profile.sometimesFields).toEqual([]);
  });
});

describe("buildActionProfile", () => {
  it("splits always/sometimes fields across instances", () => {
    const instances: ActionInstance[] = [
      {
        type: "Cast",
        gameLabel: "2026-03-29_16-10-08",
        fields: ["instanceId", "grpId", "manaCost"],
        grpId: 1000,
        instanceId: 500,
        raw: {},
      },
      {
        type: "Cast",
        gameLabel: "2026-03-30_12-00-00",
        fields: ["instanceId", "grpId", "facetId"],
        grpId: 1001,
        instanceId: 501,
        raw: {},
      },
    ];

    const profile = buildActionProfile("Cast", instances);

    expect(profile.type).toBe("Cast");
    expect(profile.occurrences).toBe(2);
    expect(profile.games).toBe(2);

    // instanceId + grpId appear in both → always
    expect(profile.alwaysFields).toContain("instanceId");
    expect(profile.alwaysFields).toContain("grpId");

    // manaCost, facetId → sometimes
    const sometimesNames = profile.sometimesFields.map((f) => f.field);
    expect(sometimesNames).toContain("manaCost");
    expect(sometimesNames).toContain("facetId");

    expect(profile.firstSeen).toBe("2026-03-29_16-10-08");
    expect(profile.lastSeen).toBe("2026-03-30_12-00-00");
  });

  it("handles empty fields gracefully", () => {
    const instances: ActionInstance[] = [
      {
        type: "Pass",
        gameLabel: "game1",
        fields: [],
        grpId: undefined,
        instanceId: undefined,
        raw: {},
      },
    ];

    const profile = buildActionProfile("Pass", instances);
    expect(profile.alwaysFields).toEqual([]);
    expect(profile.sometimesFields).toEqual([]);
    expect(profile.occurrences).toBe(1);
  });
});
