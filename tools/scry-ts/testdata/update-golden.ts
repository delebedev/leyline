#!/usr/bin/env bun
/**
 * Regenerate golden test files from the reference game.
 * Run after intentional changes to parser/accumulator behavior.
 *
 * Usage: bun run testdata/update-golden.ts
 */

import { readFileSync, writeFileSync } from "fs";
import { join } from "path";
import { parseLog } from "../src/parser";
import { detectGames } from "../src/games";
import { Accumulator } from "../src/accumulator";

const TESTDATA = join(import.meta.dir);
const GOLDEN = join(TESTDATA, "golden");

const lines = readFileSync(join(TESTDATA, "reference-brawl.log"), "utf8")
  .split("\n")
  .filter(Boolean);
const events = [...parseLog(lines)];
const games = detectGames(events);
const g = games[0];

// 1. gsm-raw-2
const gsm2 = g.greMessages.find((m) => m.gsId === 2)!;
writeFileSync(join(GOLDEN, "gsm-raw-2.json"), JSON.stringify(gsm2.raw, null, 2) + "\n");

// 2. accumulated-gs87
const acc87 = new Accumulator();
for (const gsm of g.greMessages) {
  acc87.apply(gsm.raw);
  if (gsm.gsId === 87) break;
}
const s87 = acc87.current!;
writeFileSync(
  join(GOLDEN, "accumulated-gs87.json"),
  JSON.stringify(
    {
      gameStateId: s87.gameStateId,
      turnNumber: s87.turnInfo?.turnNumber,
      life: { seat1: s87.players.get(1)?.lifeTotal, seat2: s87.players.get(2)?.lifeTotal },
      objectCount: s87.objects.size,
      zoneSnapshot: Object.fromEntries(
        [...s87.zones].map(([k, z]) => [z.type, z.objectIds.length])
      ),
      persistentAnnotationCount: s87.persistentAnnotations.size,
    },
    null,
    2
  ) + "\n"
);

// 3. gsm-with-ability
for (const gsm of g.greMessages) {
  const objs = gsm.raw.gameObjects ?? [];
  const ability = objs.find((o: any) => o.type === "GameObjectType_Ability");
  if (ability) {
    writeFileSync(
      join(GOLDEN, "gsm-with-ability.json"),
      JSON.stringify(
        {
          gsId: gsm.gsId,
          ability: {
            instanceId: ability.instanceId,
            grpId: ability.grpId,
            objectSourceGrpId: ability.objectSourceGrpId,
            parentId: ability.parentId,
          },
        },
        null,
        2
      ) + "\n"
    );
    break;
  }
}

// 4. inspect-commander
const accFull = new Accumulator();
for (const gsm of g.greMessages) accFull.apply(gsm.raw);
const sf = accFull.current!;
let commander = null;
for (const [, obj] of sf.objects) {
  if (obj.grpId === 96082) {
    commander = obj;
    break;
  }
}
const cmdAnns: any[] = [];
if (commander) {
  for (const [, ann] of sf.persistentAnnotations) {
    if (
      (ann.affectedIds ?? []).includes(commander.instanceId) ||
      ann.affectorId === commander.instanceId
    ) {
      cmdAnns.push(ann);
    }
  }
}
writeFileSync(
  join(GOLDEN, "inspect-commander.json"),
  JSON.stringify(
    {
      object: commander,
      persistentAnnotationCount: cmdAnns.length,
      designationPresent: cmdAnns.some((a: any) =>
        (a.type ?? []).some((t: string) => t.includes("Designation"))
      ),
    },
    null,
    2
  ) + "\n"
);

console.log("Golden files updated.");
