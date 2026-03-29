import { describe, it, expect } from "bun:test";
import { Accumulator } from "./accumulator";

function fullGsm(gsId: number, overrides: any = {}) {
  return {
    gameStateId: gsId,
    type: "GameStateType_Full",
    turnInfo: { turnNumber: 1, phase: "Phase_Main1", step: "", activePlayer: 1, priorityPlayer: 1, decisionPlayer: 0 },
    players: [
      { systemSeatNumber: 1, lifeTotal: 20 },
      { systemSeatNumber: 2, lifeTotal: 20 },
    ],
    zones: [
      { zoneId: 28, type: "ZoneType_Battlefield", objectInstanceIds: [] },
      { zoneId: 31, type: "ZoneType_Hand", ownerSeatId: 1, objectInstanceIds: [100, 101] },
    ],
    gameObjects: [
      { instanceId: 100, grpId: 1000, type: "GameObjectType_Card", zoneId: 31, ownerSeatId: 1, controllerSeatId: 1 },
      { instanceId: 101, grpId: 1001, type: "GameObjectType_Card", zoneId: 31, ownerSeatId: 1, controllerSeatId: 1 },
    ],
    annotations: [],
    actions: [],
    ...overrides,
  };
}

function diffGsm(gsId: number, overrides: any = {}) {
  return {
    gameStateId: gsId,
    type: "GameStateType_Diff",
    ...overrides,
  };
}

describe("Accumulator", () => {
  it("applies a Full GSM", () => {
    const acc = new Accumulator();
    acc.apply(fullGsm(1));

    expect(acc.current).not.toBeNull();
    expect(acc.current!.gameStateId).toBe(1);
    expect(acc.current!.objects.size).toBe(2);
    expect(acc.current!.zones.size).toBe(2);
    expect(acc.current!.players.size).toBe(2);
    expect(acc.current!.players.get(1)!.lifeTotal).toBe(20);
  });

  it("merges a Diff — new objects added", () => {
    const acc = new Accumulator();
    acc.apply(fullGsm(1));
    acc.apply(diffGsm(2, {
      gameObjects: [
        { instanceId: 200, grpId: 2000, type: "GameObjectType_Card", zoneId: 28, ownerSeatId: 1, controllerSeatId: 1 },
      ],
      zones: [
        { zoneId: 28, type: "ZoneType_Battlefield", objectInstanceIds: [200] },
      ],
    }));

    expect(acc.current!.gameStateId).toBe(2);
    // Original objects preserved + new one
    expect(acc.current!.objects.size).toBe(3);
    expect(acc.current!.objects.get(200)!.grpId).toBe(2000);
    // Battlefield updated
    expect(acc.current!.zones.get(28)!.objectIds).toEqual([200]);
    // Hand unchanged
    expect(acc.current!.zones.get(31)!.objectIds).toEqual([100, 101]);
  });

  it("diff object replaces entirely (proto3 semantics)", () => {
    const acc = new Accumulator();
    acc.apply(fullGsm(1, {
      gameObjects: [
        { instanceId: 100, grpId: 1000, type: "GameObjectType_Card", zoneId: 31, ownerSeatId: 1, controllerSeatId: 1, isTapped: true, damage: 3 },
      ],
    }));

    expect(acc.current!.objects.get(100)!.isTapped).toBe(true);
    expect(acc.current!.objects.get(100)!.damage).toBe(3);

    // Diff sends object without isTapped/damage → they go to default (false/0)
    acc.apply(diffGsm(2, {
      gameObjects: [
        { instanceId: 100, grpId: 1000, type: "GameObjectType_Card", zoneId: 28, ownerSeatId: 1, controllerSeatId: 1 },
      ],
    }));

    expect(acc.current!.objects.get(100)!.isTapped).toBe(false);
    expect(acc.current!.objects.get(100)!.damage).toBe(0);
    expect(acc.current!.objects.get(100)!.zoneId).toBe(28);
  });

  it("handles diffDeletedInstanceIds", () => {
    const acc = new Accumulator();
    acc.apply(fullGsm(1));
    expect(acc.current!.objects.has(100)).toBe(true);

    acc.apply(diffGsm(2, { diffDeletedInstanceIds: [100] }));
    expect(acc.current!.objects.has(100)).toBe(false);
    expect(acc.current!.objects.has(101)).toBe(true);
  });

  it("merges players by seat", () => {
    const acc = new Accumulator();
    acc.apply(fullGsm(1));

    acc.apply(diffGsm(2, {
      players: [{ systemSeatNumber: 1, lifeTotal: 17 }],
    }));

    expect(acc.current!.players.get(1)!.lifeTotal).toBe(17);
    expect(acc.current!.players.get(2)!.lifeTotal).toBe(20); // unchanged
  });

  it("updates turnInfo from diff", () => {
    const acc = new Accumulator();
    acc.apply(fullGsm(1));

    acc.apply(diffGsm(2, {
      turnInfo: { turnNumber: 2, phase: "Phase_Combat", step: "Step_DeclareAttack", activePlayer: 2 },
    }));

    expect(acc.current!.turnInfo!.turnNumber).toBe(2);
    expect(acc.current!.turnInfo!.phase).toBe("Phase_Combat");
    expect(acc.current!.turnInfo!.step).toBe("Step_DeclareAttack");
  });

  it("preserves turnInfo when diff has none", () => {
    const acc = new Accumulator();
    acc.apply(fullGsm(1));
    acc.apply(diffGsm(2, {}));

    expect(acc.current!.turnInfo!.phase).toBe("Phase_Main1");
  });

  it("annotations are ephemeral (not cumulative)", () => {
    const acc = new Accumulator();
    acc.apply(fullGsm(1, {
      annotations: [{ id: 1, type: ["AnnotationType_ZoneTransfer"] }],
    }));
    expect(acc.current!.annotations).toHaveLength(1);

    acc.apply(diffGsm(2, {
      annotations: [{ id: 2, type: ["AnnotationType_DamageDealt"] }],
    }));
    expect(acc.current!.annotations).toHaveLength(1);
    expect(acc.current!.annotations[0].id).toBe(2);

    // Diff with no annotations clears them
    acc.apply(diffGsm(3, {}));
    expect(acc.current!.annotations).toHaveLength(0);
  });

  it("tracks ObjectIdChanged — forward chain", () => {
    const acc = new Accumulator();
    acc.apply(fullGsm(1));

    acc.apply(diffGsm(2, {
      annotations: [{
        type: ["AnnotationType_ObjectIdChanged"],
        affectedIds: [100],
        details: [
          { key: "orig_id", valueInt32: [100] },
          { key: "new_id", valueInt32: [200] },
        ],
      }],
      gameObjects: [
        { instanceId: 200, grpId: 1000, type: "GameObjectType_Card", zoneId: 33, ownerSeatId: 1, controllerSeatId: 1 },
      ],
    }));

    expect(acc.resolveId(100)).toBe(200);
    expect(acc.findObject(100)!.instanceId).toBe(200);
  });

  it("tracks ObjectIdChanged — multi-hop chain", () => {
    const acc = new Accumulator();
    acc.apply(fullGsm(1));

    // 100 → 200
    acc.apply(diffGsm(2, {
      annotations: [{
        type: ["AnnotationType_ObjectIdChanged"],
        details: [
          { key: "orig_id", valueInt32: [100] },
          { key: "new_id", valueInt32: [200] },
        ],
      }],
      gameObjects: [{ instanceId: 200, grpId: 1000, type: "GameObjectType_Card", zoneId: 33, ownerSeatId: 1 }],
    }));

    // 200 → 300
    acc.apply(diffGsm(3, {
      annotations: [{
        type: ["AnnotationType_ObjectIdChanged"],
        details: [
          { key: "orig_id", valueInt32: [200] },
          { key: "new_id", valueInt32: [300] },
        ],
      }],
      gameObjects: [{ instanceId: 300, grpId: 1000, type: "GameObjectType_Card", zoneId: 28, ownerSeatId: 1 }],
    }));

    expect(acc.resolveId(100)).toBe(300);
    expect(acc.resolveId(200)).toBe(300);
    expect(acc.traceBack(300)).toEqual([100, 200, 300]);
  });

  it("Full GSM resets id chain", () => {
    const acc = new Accumulator();
    acc.apply(fullGsm(1));

    acc.apply(diffGsm(2, {
      annotations: [{
        type: ["AnnotationType_ObjectIdChanged"],
        details: [
          { key: "orig_id", valueInt32: [100] },
          { key: "new_id", valueInt32: [200] },
        ],
      }],
    }));

    expect(acc.resolveId(100)).toBe(200);

    // New Full resets
    acc.apply(fullGsm(3));
    expect(acc.resolveId(100)).toBe(100);
  });

  it("tracks persistent annotations", () => {
    const acc = new Accumulator();
    acc.apply(fullGsm(1));

    acc.apply(diffGsm(2, {
      persistentAnnotations: [
        { id: 50, type: ["AnnotationType_LayeredEffectCreated"], affectorId: 100 },
        { id: 51, type: ["AnnotationType_AddAbility"], affectorId: 101 },
      ],
    }));

    expect(acc.current!.persistentAnnotations.size).toBe(2);
    expect(acc.current!.persistentAnnotations.get(50)!.affectorId).toBe(100);

    // Delete one
    acc.apply(diffGsm(3, {
      diffDeletedPersistentAnnotationIds: [50],
    }));

    expect(acc.current!.persistentAnnotations.size).toBe(1);
    expect(acc.current!.persistentAnnotations.has(51)).toBe(true);
  });

  it("keeps history window", () => {
    const acc = new Accumulator();
    acc.apply(fullGsm(1));
    for (let i = 2; i <= 10; i++) {
      acc.apply(diffGsm(i, {}));
    }

    // History capped at 8
    expect(acc.getState(1)).toBeNull(); // evicted (9 states, max 8)
    expect(acc.getState(2)).toBeNull(); // evicted
    expect(acc.getState(3)).not.toBeNull(); // kept
    expect(acc.getState(10)).not.toBeNull(); // latest
  });

  it("objectsInZone helper", () => {
    const acc = new Accumulator();
    acc.apply(fullGsm(1, {
      zones: [
        { zoneId: 28, type: "ZoneType_Battlefield", objectInstanceIds: [100] },
        { zoneId: 31, type: "ZoneType_Hand", ownerSeatId: 1, objectInstanceIds: [101] },
        { zoneId: 35, type: "ZoneType_Hand", ownerSeatId: 2, objectInstanceIds: [102] },
      ],
      gameObjects: [
        { instanceId: 100, grpId: 1000, type: "GameObjectType_Card", zoneId: 28, ownerSeatId: 1 },
        { instanceId: 101, grpId: 1001, type: "GameObjectType_Card", zoneId: 31, ownerSeatId: 1 },
        { instanceId: 102, grpId: 1002, type: "GameObjectType_Card", zoneId: 35, ownerSeatId: 2 },
      ],
    }));

    expect(acc.objectsInZone("Battlefield")).toHaveLength(1);
    expect(acc.objectsInZone("Hand")).toHaveLength(2);
    expect(acc.objectsInZone("Hand", 1)).toHaveLength(1);
    expect(acc.objectsInZone("Hand", 2)).toHaveLength(1);
    expect(acc.objectsInZone("Graveyard")).toHaveLength(0);
  });

  it("reset clears everything", () => {
    const acc = new Accumulator();
    acc.apply(fullGsm(1));
    expect(acc.current).not.toBeNull();

    acc.reset();
    expect(acc.current).toBeNull();
    expect(acc.getState(1)).toBeNull();
    expect(acc.resolveId(100)).toBe(100);
  });

  it("gameInfo preserved across diffs", () => {
    const acc = new Accumulator();
    acc.apply(fullGsm(1, {
      gameInfo: { matchID: "test-123", gameNumber: 1 },
    }));

    acc.apply(diffGsm(2, {}));
    expect(acc.current!.gameInfo!.matchID).toBe("test-123");

    // Diff with new gameInfo replaces
    acc.apply(diffGsm(3, {
      gameInfo: { matchID: "test-123", results: [{ result: "ResultType_WinLoss", winningTeamId: 1 }] },
    }));
    expect(acc.current!.gameInfo!.results).toHaveLength(1);
  });
});
