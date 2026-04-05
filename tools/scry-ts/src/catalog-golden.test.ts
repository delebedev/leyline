/**
 * Golden test for the catalog builder — runs annotation collection
 * against reference-brawl.log and verifies profile shapes.
 */

import { describe, test, expect } from "bun:test";
import { readFileSync } from "fs";
import { join } from "path";
import { parseLog } from "./parser";
import { detectGames } from "./games";
import { buildAnnotationProfile, type AnnotationInstance } from "./catalog-builder";
import { stripPrefix } from "./format";

const TESTDATA = join(import.meta.dir, "..", "testdata");

function loadReferenceGame() {
  const lines = readFileSync(join(TESTDATA, "reference-brawl.log"), "utf8")
    .split("\n")
    .filter(Boolean);
  const events = [...parseLog(lines)];
  const games = detectGames(events);
  return games[0];
}

/** Collect annotations from all GSMs in a game — mirrors collectAnnotationsFromGsm logic. */
function collectAnnotations(game: ReturnType<typeof loadReferenceGame>): Map<string, AnnotationInstance[]> {
  const out = new Map<string, AnnotationInstance[]>();
  const gameLabel = "reference-brawl";

  for (const gsm of game.greMessages) {
    const rawAnns = gsm.raw.annotations ?? [];
    const rawPAnns = gsm.raw.persistentAnnotations ?? [];
    const allAnns = [...rawAnns, ...rawPAnns];
    if (allAnns.length === 0) continue;

    // All annotation types in this GSM for co-type analysis
    const gsmTypeSet = new Set<string>();
    for (const ann of allAnns) {
      for (const t of ann.type ?? []) {
        const stripped = stripPrefix(t, "AnnotationType_");
        if (stripped) gsmTypeSet.add(stripped);
      }
    }
    const gsmTypes = [...gsmTypeSet];
    const gsmId = gsm.gsId;
    const pAnnSet = new Set(rawPAnns);

    for (const ann of allAnns) {
      const keys: string[] = [];
      const values: Record<string, string | number> = {};
      for (const d of ann.details ?? []) {
        const key = d.key;
        if (!key) continue;
        keys.push(key);
        const val = d.valueInt32?.[0] ?? d.valueUint32?.[0] ?? d.valueString?.[0] ?? null;
        if (val != null) values[key] = val;
      }

      const isPersistent = pAnnSet.has(ann);

      for (const t of ann.type ?? []) {
        const type = stripPrefix(t, "AnnotationType_");
        if (!type) continue;

        const inst: AnnotationInstance = {
          type,
          keys,
          values,
          isPersistent,
          gameLabel,
          gsmId,
          affectorId: ann.affectorId,
          affectedIds: ann.affectedIds ?? [],
          coTypes: gsmTypes.filter((ct) => ct !== type),
        };

        const list = out.get(type) ?? [];
        list.push(inst);
        out.set(type, list);
      }
    }
  }

  return out;
}

describe("catalog-golden: reference-brawl", () => {
  const game = loadReferenceGame();
  const annotations = collectAnnotations(game);

  test("collects many annotation types", () => {
    expect(annotations.size).toBeGreaterThan(5);
  });

  test("ZoneTransfer profile shape", () => {
    const instances = annotations.get("ZoneTransfer");
    expect(instances).toBeTruthy();
    expect(instances!.length).toBeGreaterThan(0);

    const profile = buildAnnotationProfile("ZoneTransfer", instances!);
    expect(profile.instances).toBeGreaterThan(0);
    expect(profile.games).toBe(1);
    expect(profile.alwaysKeys).toContain("zone_src");
    expect(profile.alwaysKeys).toContain("zone_dest");
    expect(profile.alwaysKeys).toContain("category");
    expect(profile.persistence).toBe("transient");
  });

  test("Designation profile persistence", () => {
    const instances = annotations.get("Designation");
    expect(instances).toBeTruthy();
    expect(instances!.length).toBeGreaterThan(0);

    const profile = buildAnnotationProfile("Designation", instances!);
    expect(profile.persistence).toBe("persistent");
  });
});
