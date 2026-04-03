/**
 * `scry variance` — annotation detail key profiling across saved game logs.
 *
 * Per annotation type: instance count, always/sometimes detail keys,
 * value samples, persistence, co-type bundles.
 */

import { loadCatalog, readSavedGame } from "../catalog";
import { loadMeta } from "../meta";
import { parseSavedSourceFilter, matchesSource } from "../provenance";
import { parseLog } from "../parser";
import { detectGames, type GsmSummary } from "../games";
import { resolveGame, parseGameFlag } from "../resolve";

// --- Types ---

interface TypeProfile {
  type: string;
  instanceCount: number;
  gameLabels: Set<string>;
  alwaysKeys: string[];
  sometimesKeys: { key: string; pct: number }[];
  valueSamples: Record<string, Set<string>>;
  persistence: Record<string, number>; // "transient" | "persistent" → count
  coTypes: Map<string, number>; // other annotation types in same GSM → count
}

interface AnnotationInstance {
  type: string;
  keys: string[];
  values: Record<string, string | number>;
  isPersistent: boolean;
  coTypes: string[]; // other annotation types in the same GSM
  gameLabel: string;
}

// --- Command ---

export async function varianceCommand(args: string[]) {
  if (args[0] === "--help" || args[0] === "-h") {
    console.log("Usage: scry variance [--type TYPE] [--summary] [--json] [--source SRC]\n");
    console.log("Profile annotation detail key shapes across saved game logs.\n");
    console.log("Options:");
    console.log("  --type TYPE       Filter to one annotation type");
    console.log("  --summary         Compact one-line-per-type table");
    console.log("  --json            Machine-readable output");
    console.log("  --game REF        Single game (substring match)");
    console.log("  --source SRC      Game sources (default: real,unknown)");
    console.log("  --diff L R        Compare two sources (e.g. --diff real leyline)");
    return;
  }

  const jsonOutput = args.includes("--json");
  const summary = args.includes("--summary");
  const typeFilter = parseFlag(args, "--type");
  const diffSources = parseDiffFlag(args);

  // --diff mode
  if (diffSources) {
    const [leftSrc, rightSrc] = diffSources;
    const leftInstances = await collectInstances(args, new Set(leftSrc.split(",")) as any);
    const rightInstances = await collectInstances(args, new Set(rightSrc.split(",")) as any);
    const leftProfiles = buildProfiles(leftInstances, typeFilter);
    const rightProfiles = buildProfiles(rightInstances, typeFilter);
    if (jsonOutput) {
      renderDiffJson(leftProfiles, rightProfiles, leftSrc, rightSrc);
    } else {
      renderDiff(leftProfiles, rightProfiles, leftSrc, rightSrc);
    }
    return;
  }

  const instances = await collectInstances(args, null);
  if (instances.length === 0) {
    console.error("No annotations found.");
    process.exit(1);
  }

  const profiles = buildProfiles(instances, typeFilter);

  if (jsonOutput) {
    renderJson(profiles);
  } else if (summary) {
    renderSummary(profiles);
  } else {
    renderDetail(profiles);
  }
}

// --- Data collection ---

async function collectInstances(
  args: string[],
  sourceOverride: Set<string> | null,
): Promise<AnnotationInstance[]> {
  const gsms = await loadAllGsms(args, sourceOverride);
  const instances: AnnotationInstance[] = [];

  for (const { gsm, gameLabel, persistentTypes } of gsms) {
    const rawAnns: any[] = gsm.raw.annotations ?? [];
    const rawPAnns: any[] = gsm.raw.persistentAnnotations ?? [];
    const allAnns = [...rawAnns, ...rawPAnns];
    if (allAnns.length === 0) continue;

    // Collect all types in this GSM for co-type analysis
    const gsmTypes: string[] = [];
    for (const ann of allAnns) {
      const t = primaryType(ann);
      if (t) gsmTypes.push(t);
    }

    const pAnnSet = new Set(rawPAnns);
    for (const ann of allAnns) {
      const type = primaryType(ann);
      if (!type) continue;

      const keys: string[] = [];
      const values: Record<string, string | number> = {};
      for (const d of ann.details ?? []) {
        const key = d.key;
        if (!key) continue;
        keys.push(key);
        // Extract first value from whichever field is populated
        const val = d.valueInt32?.[0] ?? d.valueUint32?.[0] ?? d.valueString?.[0] ?? null;
        if (val != null) values[key] = val;
      }

      instances.push({
        type,
        keys,
        values,
        isPersistent: pAnnSet.has(ann),
        coTypes: gsmTypes.filter((t) => t !== type),
        gameLabel,
      });
    }
  }

  return instances;
}

interface GsmWithContext {
  gsm: GsmSummary;
  gameLabel: string;
  persistentTypes: Set<string>;
}

async function loadAllGsms(
  args: string[],
  sourceOverride: Set<string> | null,
): Promise<GsmWithContext[]> {
  const gameRef = parseGameFlag(args);
  const results: GsmWithContext[] = [];

  if (gameRef) {
    const resolved = await resolveGame(args);
    const persistentTypes = collectPersistentTypes(resolved.game.greMessages);
    for (const gsm of resolved.game.greMessages) {
      results.push({ gsm, gameLabel: resolved.label, persistentTypes });
    }
    return results;
  }

  const catalog = loadCatalog();
  const allowedSources = sourceOverride ?? parseSavedSourceFilter(args);

  for (const entry of catalog.games) {
    const meta = loadMeta(entry.file);
    if (!matchesSource(meta, allowedSources as any)) continue;
    const lines = readSavedGame(entry.file);
    const events = [...parseLog(lines)];
    const games = detectGames(events);
    for (const game of games) {
      const label = entry.file.replace(".log", "");
      const persistentTypes = collectPersistentTypes(game.greMessages);
      for (const gsm of game.greMessages) {
        results.push({ gsm, gameLabel: label, persistentTypes });
      }
    }
  }

  return results;
}

/** Collect all annotation types that ever appear as persistent across a game. */
function collectPersistentTypes(gsms: GsmSummary[]): Set<string> {
  const types = new Set<string>();
  for (const gsm of gsms) {
    for (const pAnn of gsm.raw.persistentAnnotations ?? []) {
      const t = primaryType(pAnn);
      if (t) types.add(t);
    }
  }
  return types;
}

// --- Profile building ---

function buildProfiles(
  instances: AnnotationInstance[],
  typeFilter: string | null,
): TypeProfile[] {
  const byType = new Map<string, AnnotationInstance[]>();
  for (const inst of instances) {
    if (typeFilter && !inst.type.toLowerCase().includes(typeFilter.toLowerCase())) continue;
    const list = byType.get(inst.type) ?? [];
    list.push(inst);
    byType.set(inst.type, list);
  }

  const profiles: TypeProfile[] = [];
  for (const [type, insts] of byType) {
    const total = insts.length;
    const gameLabels = new Set(insts.map((i) => i.gameLabel));

    // Key frequency
    const keyCounts = new Map<string, number>();
    for (const inst of insts) {
      for (const key of inst.keys) {
        keyCounts.set(key, (keyCounts.get(key) ?? 0) + 1);
      }
    }

    const alwaysKeys: string[] = [];
    const sometimesKeys: { key: string; pct: number }[] = [];
    for (const [key, count] of [...keyCounts.entries()].sort((a, b) => b[1] - a[1])) {
      if (count === total) {
        alwaysKeys.push(key);
      } else {
        sometimesKeys.push({ key, pct: Math.round((count / total) * 100) });
      }
    }

    // Value samples (capped at 10 unique per key)
    const valueSamples: Record<string, Set<string>> = {};
    for (const inst of insts) {
      for (const [key, val] of Object.entries(inst.values)) {
        if (!valueSamples[key]) valueSamples[key] = new Set();
        if (valueSamples[key].size < 10) valueSamples[key].add(String(val));
      }
    }

    // Persistence
    const persistence: Record<string, number> = {};
    for (const inst of insts) {
      const label = inst.isPersistent ? "persistent" : "transient";
      persistence[label] = (persistence[label] ?? 0) + 1;
    }

    // Co-types (deduplicated per instance — count GSMs, not occurrences)
    const coTypeCounts = new Map<string, number>();
    for (const inst of insts) {
      const unique = new Set(inst.coTypes);
      for (const ct of unique) {
        coTypeCounts.set(ct, (coTypeCounts.get(ct) ?? 0) + 1);
      }
    }

    profiles.push({
      type,
      instanceCount: total,
      gameLabels,
      alwaysKeys,
      sometimesKeys,
      valueSamples,
      persistence,
      coTypes: coTypeCounts,
    });
  }

  return profiles.sort((a, b) => b.instanceCount - a.instanceCount);
}

// --- Rendering: detail ---

function renderDetail(profiles: TypeProfile[]) {
  if (profiles.length === 0) { console.log("No matching annotations."); return; }

  for (const p of profiles) {
    console.log(`\n${p.type}  (${p.instanceCount} instances, ${p.gameLabels.size} games)`);

    // Keys
    console.log(`  Always keys:    ${p.alwaysKeys.join(", ") || "(none)"}`);
    if (p.sometimesKeys.length > 0) {
      const parts = p.sometimesKeys.map((k) => `${k.key} (${k.pct}%)`);
      console.log(`  Sometimes keys: ${parts.join(", ")}`);
    }

    // Values
    const valParts: string[] = [];
    for (const key of [...p.alwaysKeys, ...p.sometimesKeys.map((k) => k.key)]) {
      const samples = p.valueSamples[key];
      if (!samples || samples.size === 0) continue;
      const vals = [...samples];
      // Numeric range shorthand
      const nums = vals.map(Number).filter((n) => !isNaN(n));
      if (nums.length === vals.length && nums.length > 2) {
        const min = Math.min(...nums);
        const max = Math.max(...nums);
        valParts.push(`${key}=[${min}..${max}]`);
      } else {
        valParts.push(`${key}=[${vals.slice(0, 5).join(",")}]`);
      }
    }
    if (valParts.length > 0) {
      console.log(`  Values:         ${valParts.join("  ")}`);
    }

    // Persistence
    const total = p.instanceCount;
    const persParts = Object.entries(p.persistence).map(
      ([k, v]) => `${k} (${Math.round((v / total) * 100)}%)`
    );
    console.log(`  Persistence:    ${persParts.join(", ")}`);

    // Co-types (>50% frequency)
    const coTypeParts: string[] = [];
    for (const [ct, count] of [...p.coTypes.entries()].sort((a, b) => b[1] - a[1])) {
      const pct = Math.round((count / total) * 100);
      if (pct >= 50) coTypeParts.push(`${ct} (${pct}%)`);
    }
    if (coTypeParts.length > 0) {
      console.log(`  Co-types:       ${coTypeParts.join(", ")}`);
    }
  }
}

// --- Rendering: summary ---

function renderSummary(profiles: TypeProfile[]) {
  if (profiles.length === 0) { console.log("No matching annotations."); return; }

  console.log(
    `${"Type".padEnd(35)} ${"Inst".padStart(5)} ${"Games".padStart(5)}  ${"Always-keys".padEnd(40)} Sometimes-keys`
  );
  console.log("\u2014".repeat(120));

  for (const p of profiles) {
    const always = p.alwaysKeys.join(",") || "\u2014";
    const sometimes = p.sometimesKeys.length > 0
      ? p.sometimesKeys.map((k) => `${k.key}(${k.pct}%)`).join(",")
      : "\u2014";
    console.log(
      `${p.type.padEnd(35)} ${String(p.instanceCount).padStart(5)} ${String(p.gameLabels.size).padStart(5)}  ${always.padEnd(40)} ${sometimes}`
    );
  }
}

// --- Rendering: JSON ---

function renderJson(profiles: TypeProfile[]) {
  const output = {
    types: profiles.map((p) => ({
      type: p.type,
      instanceCount: p.instanceCount,
      gameCount: p.gameLabels.size,
      alwaysKeys: p.alwaysKeys,
      sometimesKeys: p.sometimesKeys,
      valueSamples: Object.fromEntries(
        Object.entries(p.valueSamples).map(([k, v]) => [k, [...v]])
      ),
      persistence: p.persistence,
      coTypes: [...p.coTypes.entries()]
        .filter(([, n]) => n / p.instanceCount >= 0.5)
        .sort((a, b) => b[1] - a[1])
        .map(([type, count]) => ({ type, pct: Math.round((count / p.instanceCount) * 100) })),
    })),
    meta: {
      totalInstances: profiles.reduce((s, p) => s + p.instanceCount, 0),
      typeCount: profiles.length,
    },
  };
  console.log(JSON.stringify(output, null, 2));
}

// --- Rendering: diff ---

function renderDiff(
  left: TypeProfile[],
  right: TypeProfile[],
  leftSrc: string,
  rightSrc: string,
) {
  const leftMap = new Map(left.map((p) => [p.type, p]));
  const rightMap = new Map(right.map((p) => [p.type, p]));
  const allTypes = new Set([...leftMap.keys(), ...rightMap.keys()]);

  // Sort by: types with differences first, then alphabetical
  const sorted = [...allTypes].sort((a, b) => {
    const la = leftMap.get(a), ra = rightMap.get(a);
    const lb = leftMap.get(b), rb = rightMap.get(b);
    const aDiff = hasDifferences(la, ra);
    const bDiff = hasDifferences(lb, rb);
    if (aDiff && !bDiff) return -1;
    if (!aDiff && bDiff) return 1;
    return a.localeCompare(b);
  });

  let diffCount = 0, matchCount = 0, leftOnly = 0, rightOnly = 0;

  for (const type of sorted) {
    const l = leftMap.get(type);
    const r = rightMap.get(type);

    if (!l) { rightOnly++; continue; }
    if (!r) { leftOnly++; continue; }

    const gaps: string[] = [];
    const lAlways = new Set(l.alwaysKeys);
    const rAlways = new Set(r.alwaysKeys);
    const missingKeys = [...lAlways].filter((k) => !rAlways.has(k));
    const extraKeys = [...rAlways].filter((k) => !lAlways.has(k));
    if (missingKeys.length > 0) gaps.push(`${rightSrc} missing always-keys: ${missingKeys.join(", ")}`);
    if (extraKeys.length > 0) gaps.push(`${rightSrc} extra always-keys: ${extraKeys.join(", ")}`);

    if (gaps.length > 0) {
      diffCount++;
      console.log(`\n${type}  MISMATCH`);
      console.log(`  ${leftSrc}: ${l.instanceCount} instances  always=[${l.alwaysKeys.join(",")}]`);
      console.log(`  ${rightSrc}: ${r.instanceCount} instances  always=[${r.alwaysKeys.join(",")}]`);
      for (const g of gaps) console.log(`  \u2192 ${g}`);
    } else {
      matchCount++;
    }
  }

  console.log(`\n--- Summary ---`);
  console.log(`MATCH: ${matchCount}  MISMATCH: ${diffCount}  ${leftSrc}-only: ${leftOnly}  ${rightSrc}-only: ${rightOnly}`);

  if (leftOnly > 0) {
    const types = [...allTypes].filter((t) => leftMap.has(t) && !rightMap.has(t));
    console.log(`\n${leftSrc}-only types: ${types.join(", ")}`);
  }
  if (rightOnly > 0) {
    const types = [...allTypes].filter((t) => !leftMap.has(t) && rightMap.has(t));
    console.log(`${rightSrc}-only types: ${types.join(", ")}`);
  }
}

function renderDiffJson(
  left: TypeProfile[],
  right: TypeProfile[],
  leftSrc: string,
  rightSrc: string,
) {
  const leftMap = new Map(left.map((p) => [p.type, p]));
  const rightMap = new Map(right.map((p) => [p.type, p]));
  const allTypes = [...new Set([...leftMap.keys(), ...rightMap.keys()])].sort();

  const types = allTypes.map((type) => {
    const l = leftMap.get(type);
    const r = rightMap.get(type);
    return {
      type,
      left: l ? { instanceCount: l.instanceCount, gameCount: l.gameLabels.size, alwaysKeys: l.alwaysKeys, sometimesKeys: l.sometimesKeys } : null,
      right: r ? { instanceCount: r.instanceCount, gameCount: r.gameLabels.size, alwaysKeys: r.alwaysKeys, sometimesKeys: r.sometimesKeys } : null,
      status: !l ? `${rightSrc}_only` : !r ? `${leftSrc}_only` : hasDifferences(l, r) ? "mismatch" : "match",
    };
  });

  console.log(JSON.stringify({ diff: { left: leftSrc, right: rightSrc }, types }, null, 2));
}

function hasDifferences(l: TypeProfile | undefined, r: TypeProfile | undefined): boolean {
  if (!l || !r) return true;
  const lSet = new Set(l.alwaysKeys);
  const rSet = new Set(r.alwaysKeys);
  if (lSet.size !== rSet.size) return true;
  for (const k of lSet) if (!rSet.has(k)) return true;
  return false;
}

// --- Helpers ---

function primaryType(ann: any): string | null {
  const types: string[] = ann.type ?? [];
  const first = types[0] ?? "";
  const stripped = first.replace("AnnotationType_", "");
  return stripped || null;
}

function parseFlag(args: string[], flag: string): string | null {
  for (let i = 0; i < args.length; i++) {
    if (args[i] === flag && i + 1 < args.length) return args[i + 1];
  }
  return null;
}

function parseDiffFlag(args: string[]): [string, string] | null {
  for (let i = 0; i < args.length; i++) {
    if (args[i] === "--diff" && i + 2 < args.length) return [args[i + 1], args[i + 2]];
  }
  return null;
}
