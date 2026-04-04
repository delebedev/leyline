/**
 * Golden tests — parse a reference game, verify accumulated state
 * and GSM content against committed snapshots.
 *
 * If these fail, either:
 * - You changed accumulator/parser behavior (update golden files)
 * - You introduced a regression (fix it)
 *
 * Update golden files: `bun run testdata/update-golden.ts`
 */

import { describe, test, expect } from "bun:test";
import { readFileSync } from "fs";
import { join } from "path";
import { parseLog } from "./parser";
import { detectGames } from "./games";
import { Accumulator } from "./accumulator";

const TESTDATA = join(import.meta.dir, "..", "testdata");
const GOLDEN = join(TESTDATA, "golden");

function loadReferenceGame() {
  const lines = readFileSync(join(TESTDATA, "reference-brawl.log"), "utf8")
    .split("\n")
    .filter(Boolean);
  const events = [...parseLog(lines)];
  const games = detectGames(events);
  return games[0];
}

function loadGolden(name: string) {
  return JSON.parse(readFileSync(join(GOLDEN, name), "utf8"));
}

describe("golden: reference-brawl", () => {
  const game = loadReferenceGame();

  test("parses expected number of GSMs", () => {
    expect(game.greMessages.length).toBe(100);
  });

  test("game metadata", () => {
    expect(game.ourSeat).toBe(1);
    expect(game.result).toBeNull(); // truncated, no result
    expect(game.active).toBe(true);
  });

  test("gsm-raw-2: initial Diff GSM structure", () => {
    const golden = loadGolden("gsm-raw-2.json");
    const gsm = game.greMessages.find((m) => m.gsId === 2);
    expect(gsm).toBeTruthy();

    const raw = gsm!.raw;
    expect(raw.gameStateId).toBe(golden.gameStateId);
    expect(raw.type).toBe(golden.type);

    // Zones match
    const goldenZoneIds = (golden.zones ?? []).map((z: any) => z.zoneId).sort();
    const rawZoneIds = (raw.zones ?? []).map((z: any) => z.zoneId).sort();
    expect(rawZoneIds).toEqual(goldenZoneIds);

    // Objects match
    const goldenObjIds = (golden.gameObjects ?? []).map((o: any) => o.instanceId).sort();
    const rawObjIds = (raw.gameObjects ?? []).map((o: any) => o.instanceId).sort();
    expect(rawObjIds).toEqual(goldenObjIds);
  });

  test("gsm-1: Full GSM has Designation persistent annotations", () => {
    const gsm1 = game.greMessages.find((m) => m.gsId === 1);
    expect(gsm1).toBeTruthy();
    expect(gsm1!.raw.type).toBe("GameStateType_Full");

    const persistent = gsm1!.raw.persistentAnnotations ?? [];
    expect(persistent.length).toBe(4); // 2 per commander (player + object level)

    const designations = persistent.filter((a: any) =>
      (a.type ?? []).some((t: string) => t.includes("Designation"))
    );
    expect(designations.length).toBe(4);
  });

  test("accumulated-gs87: board state at T5", () => {
    const golden = loadGolden("accumulated-gs87.json");
    const acc = new Accumulator();
    for (const gsm of game.greMessages) {
      acc.apply(gsm.raw);
      if (gsm.gsId === 87) break;
    }
    const s = acc.current!;

    expect(s.gameStateId).toBe(golden.gameStateId);
    expect(s.turnInfo?.turnNumber).toBe(golden.turnNumber);
    expect(s.players.get(1)?.lifeTotal).toBe(golden.life.seat1);
    expect(s.players.get(2)?.lifeTotal).toBe(golden.life.seat2);
    expect(s.objects.size).toBe(golden.objectCount);
    expect(s.persistentAnnotations.size).toBe(golden.persistentAnnotationCount);
  });

  test("gsm-with-ability: ability object on stack", () => {
    const golden = loadGolden("gsm-with-ability.json");
    const gsm = game.greMessages.find((m) => m.gsId === golden.gsId);
    expect(gsm).toBeTruthy();

    const objs = gsm!.raw.gameObjects ?? [];
    const ability = objs.find((o: any) => o.type === "GameObjectType_Ability");
    expect(ability).toBeTruthy();
    expect(ability!.instanceId).toBe(golden.ability.instanceId);
    expect(ability!.grpId).toBe(golden.ability.grpId);
    expect(ability!.objectSourceGrpId).toBe(golden.ability.objectSourceGrpId);
    expect(ability!.parentId).toBe(golden.ability.parentId);
  });

  test("inspect-commander: Black Waltz persistent state", () => {
    const golden = loadGolden("inspect-commander.json");
    const acc = new Accumulator();
    for (const gsm of game.greMessages) acc.apply(gsm.raw);
    const s = acc.current!;

    // Find commander by grpId
    let commander = null;
    for (const [, obj] of s.objects) {
      if (obj.grpId === 96082) {
        commander = obj;
        break;
      }
    }
    expect(commander).toBeTruthy();
    expect(commander!.instanceId).toBe(golden.object.instanceId);
    expect(commander!.type).toBe(golden.object.type);

    // Persistent annotations
    const anns = [];
    for (const [, ann] of s.persistentAnnotations) {
      if (
        (ann.affectedIds ?? []).includes(commander!.instanceId) ||
        ann.affectorId === commander!.instanceId
      ) {
        anns.push(ann);
      }
    }
    expect(anns.length).toBe(golden.persistentAnnotationCount);
    expect(golden.designationPresent).toBe(true);
    const hasDesignation = anns.some((a: any) =>
      (a.type ?? []).some((t: string) => t.includes("Designation"))
    );
    expect(hasDesignation).toBe(true);
  });
});
