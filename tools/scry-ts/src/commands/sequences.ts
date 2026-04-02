/**
 * `scry sequences` — extract canonical GSM bracketing patterns from recordings.
 *
 * Classifies every GSM by role, detects multi-GSM interaction instances,
 * aggregates across saved games, and reports canonical sequences with variants.
 */

import { loadCatalog, readSavedGame } from "../catalog";
import { loadMeta } from "../meta";
import { parseSavedSourceFilter, matchesSource } from "../provenance";
import { parseLog } from "../parser";
import { detectGames, type Game, type GsmSummary, type GreStreamEntry } from "../games";
import { resolveGame, parseGameFlag } from "../resolve";
import {
  classifyGsm,
  extractInstanceId,
  extractIdChanges,
  type GsmRole,
} from "../classifier";

// --- Types ---

interface ClassifiedGsm {
  gsm: GsmSummary;
  role: GsmRole;
  streamIndex: number; // position in greStream
}

interface InteractionInstance {
  type: InteractionType;
  slots: ClassifiedSlot[];
  gameLabel: string;
}

interface ClassifiedSlot {
  role: GsmRole;
  gsm: GsmSummary;
  updateType: string;
  followedBy: string | null;
  annotationTypes: string[];
  gsIdDelta: number;
}

type InteractionType =
  | "TARGETED_SPELL"
  | "UNTARGETED_SPELL"
  | "COMBAT_DAMAGE"
  | "LAND_PLAY"
  | "DRAW"
  | "ETB_TRIGGER";

interface AggregatedInteraction {
  type: InteractionType;
  instances: InteractionInstance[];
  gameCount: number;
  canonical: GsmRole[];
  variants: { sequence: GsmRole[]; count: number }[];
  slots: AggregatedSlot[];
}

interface AggregatedSlot {
  index: number;
  role: GsmRole;
  updateType: Record<string, number>;
  followedBy: Record<string, number>;
  annotations: { always: string[]; sometimes: Record<string, number> };
  gsIdDelta: Record<string, number>;
}

// --- Abbreviation map for human output ---

const ABBREV: Record<string, string> = {
  ObjectIdChanged: "OIC",
  ZoneTransfer: "ZT",
  PlayerSelectingTargets: "PST",
  PlayerSubmittedTargets: "PSuT",
  ResolutionStart: "RS",
  ResolutionComplete: "RC",
  DamageDealt: "DD",
  ModifiedLife: "ML",
  SyntheticEvent: "SE",
  PhaseOrStepModified: "POS",
  UserActionTaken: "UAT",
  AbilityInstanceCreated: "AIC",
  AbilityInstanceDeleted: "AID",
  ManaPaid: "MP",
  TokenCreated: "TC",
  Scry: "Scry",
  Surveil: "Surv",
  LayeredEffectCreated: "LEC",
  LayeredEffectDestroyed: "LED",
  TappedUntappedPermanent: "TUP",
  CounterAdded: "CA",
  CounterRemoved: "CR",
  ManaDetails: "MD",
};

function abbreviate(type: string): string {
  // Try exact match first
  if (ABBREV[type]) return ABBREV[type];
  // Try stripping prefix
  const stripped = type.replace("AnnotationType_", "");
  if (ABBREV[stripped]) return ABBREV[stripped];
  // Shorten ZoneTransfer(Category) style
  if (stripped.startsWith("ZoneTransfer")) return "ZT";
  return stripped;
}

// --- Command ---

export async function sequencesCommand(args: string[]) {
  if (args[0] === "--help" || args[0] === "-h") {
    console.log("Usage: scry sequences [--game REF] [--type TYPE] [--json] [--source SRC]\n");
    console.log("Extract canonical GSM bracketing patterns from saved recordings.\n");
    console.log("Options:");
    console.log("  --game REF     Single game (substring match)");
    console.log("  --type TYPE    Filter interaction type (targeted_spell, untargeted_spell,");
    console.log("                 combat_damage, land_play, draw, etb_trigger)");
    console.log("  --json         Machine-readable JSON output");
    console.log("  --source SRC   Game sources (default: real,unknown). Use 'any' for all");
    console.log("  --debug        Show per-game classification details");
    return;
  }

  const jsonOutput = args.includes("--json");
  const debug = args.includes("--debug");
  const typeFilter = parseFlag(args, "--type")?.toUpperCase() ?? null;

  // Load games
  const games = await loadGames(args);
  if (games.length === 0) {
    console.error("No games found.");
    process.exit(1);
  }

  // Process all games
  const allInstances: InteractionInstance[] = [];
  const gameLabels = new Set<string>();

  for (const { game, label } of games) {
    gameLabels.add(label);
    const classified = classifyStream(game);
    const instances = detectInteractions(classified, game, label);
    allInstances.push(...instances);

    if (debug) {
      console.log(`\n--- ${label}: ${classified.length} classified GSMs, ${instances.length} interactions ---`);
      for (const c of classified) {
        console.log(`  gs${c.gsm.gsId} ${c.role.padEnd(20)} [${c.gsm.annotationTypes.join(", ")}]`);
      }
    }
  }

  // Filter by type
  const filtered = typeFilter
    ? allInstances.filter((i) => i.type === typeFilter)
    : allInstances;

  // Aggregate
  const aggregated = aggregate(filtered, gameLabels.size);

  // Render
  if (jsonOutput) {
    renderJson(aggregated, games.length);
  } else {
    renderHuman(aggregated);
  }
}

// --- Game loading ---

interface LoadedGame {
  game: Game;
  label: string;
}

async function loadGames(args: string[]): Promise<LoadedGame[]> {
  const gameRef = parseGameFlag(args);

  // Single game mode
  if (gameRef) {
    const resolved = await resolveGame(args);
    return [{ game: resolved.game, label: resolved.label }];
  }

  // All saved games mode (default)
  const catalog = loadCatalog();
  const allowedSources = parseSavedSourceFilter(args);
  const results: LoadedGame[] = [];

  for (const entry of catalog.games) {
    const meta = loadMeta(entry.file);
    if (!matchesSource(meta, allowedSources)) continue;

    const lines = readSavedGame(entry.file);
    const events = [...parseLog(lines)];
    const detected = detectGames(events);
    for (const game of detected) {
      results.push({ game, label: entry.file.replace(".log", "") });
    }
  }

  return results;
}

// --- Classification ---

function classifyStream(game: Game): ClassifiedGsm[] {
  const result: ClassifiedGsm[] = [];
  for (let i = 0; i < game.greStream.length; i++) {
    const entry = game.greStream[i];
    if (entry.kind !== "gsm") continue;
    result.push({
      gsm: entry,
      role: classifyGsm(entry),
      streamIndex: i,
    });
  }
  return result;
}

// --- Interaction detection ---

function detectInteractions(
  classified: ClassifiedGsm[],
  game: Game,
  gameLabel: string,
): InteractionInstance[] {
  const instances: InteractionInstance[] = [];

  // Track ObjectIdChanged remappings across the game
  const idForward = new Map<number, number>();
  for (const c of classified) {
    for (const [orig, newId] of extractIdChanges(c.gsm)) {
      idForward.set(orig, newId);
    }
  }

  function resolveId(id: number): number {
    let current = id;
    const seen = new Set<number>();
    while (idForward.has(current) && !seen.has(current)) {
      seen.add(current);
      current = idForward.get(current)!;
    }
    return current;
  }

  const used = new Set<number>(); // gsId values already part of an interaction

  for (let i = 0; i < classified.length; i++) {
    const c = classified[i];
    if (used.has(c.gsm.gsId)) continue;

    // Single-GSM interactions
    if (c.role === "COMBAT_DAMAGE" || c.role === "COMBAT_DAMAGE_KILL") {
      instances.push(buildSingleInteraction("COMBAT_DAMAGE", c, game, gameLabel));
      used.add(c.gsm.gsId);
      continue;
    }
    if (c.role === "LAND") {
      instances.push(buildSingleInteraction("LAND_PLAY", c, game, gameLabel));
      used.add(c.gsm.gsId);
      continue;
    }
    if (c.role === "DRAW") {
      instances.push(buildSingleInteraction("DRAW", c, game, gameLabel));
      used.add(c.gsm.gsId);
      continue;
    }

    // Multi-GSM: targeted spell
    if (c.role === "CAST_TARGETED") {
      const spellId = extractInstanceId(c.gsm, c.role);
      const endIdx = findResolveFor(classified, i, spellId, idForward);
      if (endIdx !== null) {
        const window = classified.slice(i, endIdx + 1);
        instances.push(buildMultiInteraction("TARGETED_SPELL", window, game, gameLabel));
        for (const w of window) used.add(w.gsm.gsId);
      }
      continue;
    }

    // Multi-GSM: untargeted spell
    if (c.role === "CAST") {
      const spellId = extractInstanceId(c.gsm, c.role);
      const endIdx = findResolveFor(classified, i, spellId, idForward);
      if (endIdx !== null) {
        const window = classified.slice(i, endIdx + 1);
        instances.push(buildMultiInteraction("UNTARGETED_SPELL", window, game, gameLabel));
        for (const w of window) used.add(w.gsm.gsId);
      }
      continue;
    }

    // Multi-GSM: ETB trigger
    if (c.role === "TRIGGER_ENTER") {
      const abilityId = extractInstanceId(c.gsm, c.role);
      const endIdx = findTriggerResolveFor(classified, i, abilityId, idForward);
      if (endIdx !== null) {
        const window = classified.slice(i, endIdx + 1);
        instances.push(buildMultiInteraction("ETB_TRIGGER", window, game, gameLabel));
        for (const w of window) used.add(w.gsm.gsId);
      }
      continue;
    }
  }

  return instances;
}

function findResolveFor(
  classified: ClassifiedGsm[],
  startIdx: number,
  spellId: number | null,
  idForward: Map<number, number>,
): number | null {
  if (spellId == null) return null;

  // Resolve the spell ID through any remappings
  let resolvedId = spellId;
  const seen = new Set<number>();
  while (idForward.has(resolvedId) && !seen.has(resolvedId)) {
    seen.add(resolvedId);
    resolvedId = idForward.get(resolvedId)!;
  }

  // Walk forward looking for RESOLVE or RESOLVE_KILL with matching id
  for (let j = startIdx + 1; j < classified.length && j < startIdx + 30; j++) {
    const c = classified[j];
    if (c.role !== "RESOLVE" && c.role !== "RESOLVE_KILL") continue;

    const resId = extractInstanceId(c.gsm, c.role);
    if (resId == null) continue;

    // Check both original and resolved IDs
    if (resId === spellId || resId === resolvedId) return j;

    // Also check if resId resolves to the same target
    let resResolved = resId;
    const seenRes = new Set<number>();
    while (idForward.has(resResolved) && !seenRes.has(resResolved)) {
      seenRes.add(resResolved);
      resResolved = idForward.get(resResolved)!;
    }
    if (resResolved === resolvedId) return j;
  }
  return null;
}

function findTriggerResolveFor(
  classified: ClassifiedGsm[],
  startIdx: number,
  abilityId: number | null,
  idForward: Map<number, number>,
): number | null {
  if (abilityId == null) return null;

  for (let j = startIdx + 1; j < classified.length && j < startIdx + 20; j++) {
    const c = classified[j];
    if (c.role !== "TRIGGER_RESOLVE" && c.role !== "RESOLVE" && c.role !== "RESOLVE_KILL") continue;

    const resId = extractInstanceId(c.gsm, c.role);
    if (resId == null) continue;
    if (resId === abilityId) return j;
  }
  return null;
}

function buildSingleInteraction(
  type: InteractionType,
  c: ClassifiedGsm,
  game: Game,
  gameLabel: string,
): InteractionInstance {
  // Include trailing echo if present
  const slots: ClassifiedSlot[] = [buildSlot(c, game, null)];

  // Look for echo immediately after in greStream
  const nextGsm = findNextGsmInStream(game, c.streamIndex);
  if (nextGsm && classifyGsm(nextGsm.gsm) === "ECHO") {
    slots.push(buildSlot(
      { gsm: nextGsm.gsm, role: "ECHO", streamIndex: nextGsm.streamIndex },
      game,
      c.gsm,
    ));
  }

  return { type, slots, gameLabel };
}

function buildMultiInteraction(
  type: InteractionType,
  window: ClassifiedGsm[],
  game: Game,
  gameLabel: string,
): InteractionInstance {
  const slots: ClassifiedSlot[] = [];
  for (let i = 0; i < window.length; i++) {
    const prev = i > 0 ? window[i - 1].gsm : null;
    slots.push(buildSlot(window[i], game, prev));
  }

  // Include trailing echo after last GSM
  const lastEntry = window[window.length - 1];
  const nextGsm = findNextGsmInStream(game, lastEntry.streamIndex);
  if (nextGsm && classifyGsm(nextGsm.gsm) === "ECHO") {
    slots.push(buildSlot(
      { gsm: nextGsm.gsm, role: "ECHO", streamIndex: nextGsm.streamIndex },
      game,
      lastEntry.gsm,
    ));
  }

  return { type, slots, gameLabel };
}

function buildSlot(
  c: ClassifiedGsm,
  game: Game,
  prevGsm: GsmSummary | null,
): ClassifiedSlot {
  const updateType = (c.gsm.raw.update ?? "").replace("GameStateUpdate_", "");

  // Find followed_by: next non-GSM entry in greStream after this GSM.
  // Strategy 1: look for a GRE message with matching gameStateId (linked by gsId).
  // Strategy 2: fall back to the next GRE message in stream order before the next GSM.
  let followedBy: string | null = null;
  // Strategy 1: find GRE linked by gameStateId
  for (let i = c.streamIndex + 1; i < game.greStream.length; i++) {
    const entry = game.greStream[i];
    if (entry.kind === "gre" && entry.gameStateId === c.gsm.gsId) {
      followedBy = entry.type;
      break;
    }
  }
  // Strategy 2: immediate next in stream
  if (!followedBy) {
    for (let i = c.streamIndex + 1; i < game.greStream.length; i++) {
      const entry = game.greStream[i];
      if (entry.kind === "gre") { followedBy = entry.type; break; }
      if (entry.kind === "gsm") break;
    }
  }

  const gsIdDelta = prevGsm ? c.gsm.gsId - prevGsm.gsId : 0;

  return {
    role: c.role,
    gsm: c.gsm,
    updateType,
    followedBy,
    annotationTypes: c.gsm.annotationTypes,
    gsIdDelta,
  };
}

function findNextGsmInStream(
  game: Game,
  afterIndex: number,
): { gsm: GsmSummary; streamIndex: number } | null {
  for (let i = afterIndex + 1; i < game.greStream.length; i++) {
    const entry = game.greStream[i];
    if (entry.kind === "gsm") return { gsm: entry, streamIndex: i };
  }
  return null;
}

// --- Aggregation ---

function aggregate(instances: InteractionInstance[], gameCount: number): AggregatedInteraction[] {
  const byType = new Map<InteractionType, InteractionInstance[]>();
  for (const inst of instances) {
    const list = byType.get(inst.type) ?? [];
    list.push(inst);
    byType.set(inst.type, list);
  }

  const result: AggregatedInteraction[] = [];
  const typeOrder: InteractionType[] = [
    "TARGETED_SPELL", "UNTARGETED_SPELL", "ETB_TRIGGER",
    "COMBAT_DAMAGE", "LAND_PLAY", "DRAW",
  ];

  for (const type of typeOrder) {
    const insts = byType.get(type);
    if (!insts || insts.length === 0) continue;

    const gameLabels = new Set(insts.map((i) => i.gameLabel));

    // Count sequences by role pattern
    const seqCounts = new Map<string, { seq: GsmRole[]; count: number }>();
    for (const inst of insts) {
      const seq = inst.slots.map((s) => s.role);
      const key = seq.join(",");
      const entry = seqCounts.get(key) ?? { seq, count: 0 };
      entry.count++;
      seqCounts.set(key, entry);
    }

    // Find canonical (most common)
    let canonical: GsmRole[] = [];
    let maxCount = 0;
    const variants: { sequence: GsmRole[]; count: number }[] = [];
    for (const { seq, count } of seqCounts.values()) {
      if (count > maxCount) {
        if (canonical.length > 0) variants.push({ sequence: canonical, count: maxCount });
        canonical = seq;
        maxCount = count;
      } else {
        variants.push({ sequence: seq, count });
      }
    }

    // Aggregate per-slot details using canonical sequence length
    const slots = aggregateSlots(insts, canonical.length);

    result.push({
      type,
      instances: insts,
      gameCount: gameLabels.size,
      canonical,
      variants: variants.filter((v) => v.count > 0).sort((a, b) => b.count - a.count),
      slots,
    });
  }

  return result;
}

function aggregateSlots(instances: InteractionInstance[], slotCount: number): AggregatedSlot[] {
  const slots: AggregatedSlot[] = [];

  for (let si = 0; si < slotCount; si++) {
    const updateTypes: Record<string, number> = {};
    const followedBys: Record<string, number> = {};
    const annCounts = new Map<string, number>();
    const gsIdDeltas: Record<string, number> = {};
    let total = 0;

    for (const inst of instances) {
      if (si >= inst.slots.length) continue;
      const slot = inst.slots[si];
      total++;

      // updateType
      const ut = slot.updateType || "unknown";
      updateTypes[ut] = (updateTypes[ut] ?? 0) + 1;

      // followedBy
      const fb = slot.followedBy ?? "none";
      followedBys[fb] = (followedBys[fb] ?? 0) + 1;

      // annotations
      for (const ann of slot.annotationTypes) {
        annCounts.set(ann, (annCounts.get(ann) ?? 0) + 1);
      }

      // gsId delta
      const delta = String(slot.gsIdDelta);
      gsIdDeltas[delta] = (gsIdDeltas[delta] ?? 0) + 1;
    }

    // Split annotations into always vs sometimes
    const always: string[] = [];
    const sometimes: Record<string, number> = {};
    for (const [ann, count] of annCounts) {
      if (count === total) {
        always.push(ann);
      } else {
        sometimes[ann] = count;
      }
    }

    slots.push({
      index: si + 1,
      role: instances[0]?.slots[si]?.role ?? "UNKNOWN",
      updateType: updateTypes,
      followedBy: followedBys,
      annotations: { always, sometimes },
      gsIdDelta: gsIdDeltas,
    });
  }

  return slots;
}

// --- Rendering ---

function renderHuman(aggregated: AggregatedInteraction[]) {
  if (aggregated.length === 0) {
    console.log("No interactions detected.");
    return;
  }

  for (const agg of aggregated) {
    const total = agg.instances.length;
    console.log(`\n${agg.type}  ${total} instances  ${agg.gameCount} games`);

    for (const slot of agg.slots) {
      const ut = topValue(slot.updateType);
      const fb = topValue(slot.followedBy);
      const fbStr = fb === "none" ? "\u2014" : `\u2192 ${fb}`;

      // Build annotation list with ? for sometimes-present
      const annParts: string[] = [];
      for (const a of slot.annotations.always) {
        annParts.push(abbreviate(a));
      }
      for (const [a, count] of Object.entries(slot.annotations.sometimes)) {
        annParts.push(`${abbreviate(a)}?`);
      }
      const annStr = annParts.join(" ");

      console.log(
        `  [${slot.index}] ${slot.role.padEnd(22)} ${ut.padEnd(12)} ${fbStr.padEnd(24)} [${annStr}]`,
      );
    }

    // Variants
    if (agg.variants.length > 0) {
      const canonicalCount = total - agg.variants.reduce((s, v) => s + v.count, 0);
      const parts = [`${canonicalCount}/${total} canonical`];
      for (const v of agg.variants) {
        // Find which slot differs
        const diffIdx = v.sequence.findIndex((r, i) => r !== agg.canonical[i]);
        const diffRole = diffIdx >= 0 ? `${v.sequence[diffIdx]} at slot ${diffIdx + 1}` : "different length";
        parts.push(`${v.count}/${total} ${diffRole}`);
      }
      console.log(`  Variants: ${parts.join(", ")}`);
    }
  }
}

function renderJson(aggregated: AggregatedInteraction[], gamesScanned: number) {
  const output = {
    interactions: aggregated.map((agg) => ({
      type: agg.type,
      instanceCount: agg.instances.length,
      gameCount: agg.gameCount,
      canonical: agg.canonical,
      variants: agg.variants,
      slots: agg.slots.map((slot) => ({
        index: slot.index,
        role: slot.role,
        updateType: slot.updateType,
        followedBy: slot.followedBy,
        annotations: slot.annotations,
        gsIdDelta: slot.gsIdDelta,
      })),
    })),
    meta: {
      gamesScanned,
      totalInstances: aggregated.reduce((s, a) => s + a.instances.length, 0),
    },
  };

  console.log(JSON.stringify(output, null, 2));
}

// --- Helpers ---

function topValue(dist: Record<string, number>): string {
  let top = "";
  let max = 0;
  for (const [k, v] of Object.entries(dist)) {
    if (v > max) { top = k; max = v; }
  }
  return top;
}

function parseFlag(args: string[], flag: string): string | null {
  for (let i = 0; i < args.length; i++) {
    if (args[i] === flag && i + 1 < args.length) return args[i + 1];
  }
  return null;
}
