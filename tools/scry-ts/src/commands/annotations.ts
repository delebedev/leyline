/**
 * `scry annotations` — annotation analysis across all GSMs.
 *
 * Subcommands:
 *   order --type TYPE  — neighbor analysis for a specific annotation type
 */

import { loadCatalog, readSavedGame } from "../catalog";
import { loadMeta } from "../meta";
import { parseSavedSourceFilter, matchesSource } from "../provenance";
import { parseLog } from "../parser";
import { detectGames, type GsmSummary } from "../games";
import { resolveGame, parseGameFlag } from "../resolve";

export async function annotationsCommand(args: string[]) {
  const verb = args[0];

  if (!verb || verb === "--help" || verb === "-h") {
    console.log("Usage: scry annotations <command>\n");
    console.log("Commands:");
    console.log("  order    Annotation neighbor analysis");
    console.log("\nFlags:");
    console.log("  --type TYPE    Annotation type to analyze (required for order)");
    console.log("  --game REF     Single game (substring match)");
    console.log("  --source SRC   Game sources (default: real,unknown)");
    console.log("  --json         Machine-readable output");
    return;
  }

  if (verb === "order") {
    await orderCommand(args.slice(1));
  } else {
    console.error(`Unknown annotations command: ${verb}\nRun 'scry annotations --help' for usage.`);
    process.exit(1);
  }
}

async function orderCommand(args: string[]) {
  if (args[0] === "--help" || args[0] === "-h") {
    console.log("Usage: scry annotations order --type TYPE [--game REF] [--source SRC] [--json]\n");
    console.log("Show what always/usually comes before and after a given annotation type.\n");
    console.log("Examples:");
    console.log("  scry annotations order --type DamageDealt");
    console.log("  scry annotations order --type ZoneTransfer --source real");
    return;
  }

  const targetType = parseFlag(args, "--type");
  if (!targetType) {
    console.error("--type is required. Example: scry annotations order --type DamageDealt");
    process.exit(1);
  }

  const jsonOutput = args.includes("--json");

  // Load all GSMs
  const allGsms = await loadAllGsms(args);

  // Find GSMs containing the target type
  const matches: { gsm: GsmSummary; types: string[] }[] = [];
  for (const gsm of allGsms) {
    const rawAnns: any[] = gsm.raw.annotations ?? [];
    const types = rawAnns.map((a: any) => {
      const ts: string[] = a.type ?? [];
      return (ts[0] ?? "").replace("AnnotationType_", "");
    }).filter((t: string) => t.length > 0);

    if (types.some((t) => t.includes(targetType))) {
      matches.push({ gsm, types });
    }
  }

  if (matches.length === 0) {
    console.error(`No GSMs found containing annotation type: ${targetType}`);
    process.exit(1);
  }

  // Analyze neighbors
  const beforeCounts = new Map<string, number>();
  const afterCounts = new Map<string, number>();
  const colocated = new Set<string>();
  const allTypes = new Set<string>();
  const gameLabels = new Set<string>();

  for (const { gsm, types } of matches) {
    gameLabels.add(gsm.timestamp ?? "?");
    const targetIdx = types.findIndex((t) => t.includes(targetType));
    if (targetIdx < 0) continue;

    // Everything before
    for (let i = 0; i < targetIdx; i++) {
      beforeCounts.set(types[i], (beforeCounts.get(types[i]) ?? 0) + 1);
      colocated.add(types[i]);
    }
    // Everything after
    for (let i = targetIdx + 1; i < types.length; i++) {
      afterCounts.set(types[i], (afterCounts.get(types[i]) ?? 0) + 1);
      colocated.add(types[i]);
    }
    // All types ever seen (for "never colocated")
    for (const t of types) allTypes.add(t);
  }

  // Collect ALL annotation types across all GSMs for "never colocated" analysis
  const globalTypes = new Set<string>();
  for (const gsm of allGsms) {
    for (const t of gsm.annotationTypes) {
      globalTypes.add(t);
    }
  }

  const total = matches.length;
  const alwaysBefore = [...beforeCounts.entries()].filter(([, n]) => n === total).map(([t]) => t);
  const usuallyBefore = [...beforeCounts.entries()].filter(([, n]) => n >= total * 0.7 && n < total).map(([t, n]) => ({ type: t, pct: Math.round((n / total) * 100) }));
  const alwaysAfter = [...afterCounts.entries()].filter(([, n]) => n === total).map(([t]) => t);
  const usuallyAfter = [...afterCounts.entries()].filter(([, n]) => n >= total * 0.7 && n < total).map(([t, n]) => ({ type: t, pct: Math.round((n / total) * 100) }));
  const neverColocated = [...globalTypes].filter((t) => !colocated.has(t) && t !== targetType);

  if (jsonOutput) {
    console.log(JSON.stringify({
      type: targetType,
      instances: total,
      games: gameLabels.size,
      alwaysBefore,
      usuallyBefore,
      alwaysAfter,
      usuallyAfter,
      neverColocated,
    }, null, 2));
  } else {
    console.log(`${targetType}  (${total} instances across ${gameLabels.size} games)\n`);

    console.log("  Always before (100%):");
    if (alwaysBefore.length > 0) {
      for (const t of alwaysBefore) console.log(`    ${t}`);
    } else {
      console.log("    (none)");
    }

    console.log("\n  Usually before (>70%):");
    if (usuallyBefore.length > 0) {
      for (const { type, pct } of usuallyBefore) console.log(`    ${type}  (${pct}%)`);
    } else {
      console.log("    (none beyond always)");
    }

    console.log("\n  Always after (100%):");
    if (alwaysAfter.length > 0) {
      for (const t of alwaysAfter) console.log(`    ${t}`);
    } else {
      console.log("    (none)");
    }

    console.log("\n  Usually after (>70%):");
    if (usuallyAfter.length > 0) {
      for (const { type, pct } of usuallyAfter) console.log(`    ${type}  (${pct}%)`);
    } else {
      console.log("    (none beyond always)");
    }

    console.log("\n  Never colocated with:");
    if (neverColocated.length > 0) {
      for (const t of neverColocated.sort()) console.log(`    ${t}`);
    } else {
      console.log("    (none — appears with all types at least once)");
    }
  }
}

// --- Helpers ---

async function loadAllGsms(args: string[]): Promise<GsmSummary[]> {
  const gameRef = parseGameFlag(args);

  if (gameRef) {
    const resolved = await resolveGame(args);
    return resolved.game.greMessages;
  }

  // All saved games
  const catalog = loadCatalog();
  const allowedSources = parseSavedSourceFilter(args);
  const allGsms: GsmSummary[] = [];

  for (const entry of catalog.games) {
    const meta = loadMeta(entry.file);
    if (!matchesSource(meta, allowedSources)) continue;
    const lines = readSavedGame(entry.file);
    const events = [...parseLog(lines)];
    const games = detectGames(events);
    for (const game of games) {
      allGsms.push(...game.greMessages);
    }
  }

  return allGsms;
}

function parseFlag(args: string[], flag: string): string | null {
  for (let i = 0; i < args.length; i++) {
    if (args[i] === flag && i + 1 < args.length) return args[i + 1];
  }
  return null;
}
