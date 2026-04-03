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
  game: Game;
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
  annotationOrder: string[];
  orderConsistency: number;
  fieldPresence: Record<string, number>; // field name → count of instances where present
  fieldTotal: number; // total instances for this slot (for percentage calc)
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
    console.log("  --field-presence  Show GSM field presence per slot");
    console.log("  --persistent      Show persistent annotation lifecycle");
    console.log("  --gsid-gaps       Show gap analysis between slots");
    console.log("  --diff L R        Compare two sources side-by-side (e.g. --diff leyline real)");
    return;
  }

  const jsonOutput = args.includes("--json");
  const debug = args.includes("--debug");
  const showFieldPresence = args.includes("--field-presence");
  const showPersistent = args.includes("--persistent");
  const showGsidGaps = args.includes("--gsid-gaps");
  const diffSources = parseDiffFlag(args);
  const typeFilter = parseFlag(args, "--type")?.toUpperCase() ?? null;

  // --diff mode: run pipeline twice with different source filters
  if (diffSources) {
    const [leftSrc, rightSrc] = diffSources;
    const leftArgs = args.filter((a) => a !== "--diff" && a !== leftSrc && a !== rightSrc);
    const rightArgs = [...leftArgs];
    const leftGames = await loadGamesWithSource(leftArgs, new Set([leftSrc as any]));
    const rightGames = await loadGamesWithSource(rightArgs, new Set([rightSrc as any]));
    const leftInstances = processGames(leftGames, debug, typeFilter);
    const rightInstances = processGames(rightGames, debug, typeFilter);
    const leftAgg = aggregate(leftInstances, new Set(leftGames.map((g) => g.label)).size);
    const rightAgg = aggregate(rightInstances, new Set(rightGames.map((g) => g.label)).size);
    if (jsonOutput) {
      renderDiffJson(leftAgg, rightAgg, leftSrc, rightSrc, leftGames.length, rightGames.length);
    } else {
      renderDiff(leftAgg, rightAgg, leftSrc, rightSrc);
    }
    return;
  }

  // Load games
  const games = await loadGames(args);
  if (games.length === 0) {
    console.error("No games found.");
    process.exit(1);
  }

  // Process all games
  const allInstances = processGames(games, debug, typeFilter);
  const gameLabels = new Set(games.map((g) => g.label));

  // Aggregate
  const aggregated = aggregate(allInstances, gameLabels.size);

  const renderOpts = { showFieldPresence, showPersistent, showGsidGaps };

  // Render
  if (jsonOutput) {
    renderJson(aggregated, games.length, renderOpts);
  } else {
    renderHuman(aggregated, renderOpts);
  }
}

// --- Game loading ---

interface LoadedGame {
  game: Game;
  label: string;
}

function processGames(
  games: LoadedGame[],
  debug: boolean,
  typeFilter: string | null,
): InteractionInstance[] {
  const allInstances: InteractionInstance[] = [];
  for (const { game, label } of games) {
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
  return typeFilter
    ? allInstances.filter((i) => i.type === typeFilter)
    : allInstances;
}

async function loadGamesWithSource(args: string[], sources: Set<string>): Promise<LoadedGame[]> {
  const catalog = loadCatalog();
  const results: LoadedGame[] = [];
  for (const entry of catalog.games) {
    const meta = loadMeta(entry.file);
    if (!matchesSource(meta, sources as any)) continue;
    const lines = readSavedGame(entry.file);
    const events = [...parseLog(lines)];
    const detected = detectGames(events);
    for (const game of detected) {
      results.push({ game, label: entry.file.replace(".log", "") });
    }
  }
  return results;
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

  return { type, slots, gameLabel, game };
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

  return { type, slots, gameLabel, game };
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
    const orderings: string[][] = [];
    const fieldPresence: Record<string, number> = {};
    let total = 0;

    for (const inst of instances) {
      if (si >= inst.slots.length) continue;
      const slot = inst.slots[si];
      total++;

      // field presence
      const raw = slot.gsm.raw;
      const fields: [string, boolean][] = [
        ["gameInfo", raw.gameInfo != null],
        ["turnInfo", raw.turnInfo != null],
        ["players", (raw.players ?? []).length > 0],
        ["zones", (raw.zones ?? []).length > 0],
        ["gameObjects", (raw.gameObjects ?? []).length > 0],
        ["timers", (raw.timers ?? []).length > 0],
        ["annotations", (raw.annotations ?? []).length > 0],
        ["persistentAnns", (raw.persistentAnnotations ?? []).length > 0],
        ["diffDeleted", (raw.diffDeletedInstanceIds ?? []).length > 0],
        ["diffDelPAnns", (raw.diffDeletedPersistentAnnotationIds ?? []).length > 0],
        ["actions", (raw.actions ?? []).length > 0],
        ["prevGsId", raw.prevGameStateId != null && raw.prevGameStateId !== 0],
      ];
      for (const [name, present] of fields) {
        if (present) fieldPresence[name] = (fieldPresence[name] ?? 0) + 1;
      }

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

      // annotation ordering (from raw annotations array, preserving Player.log order)
      const rawAnns: any[] = slot.gsm.raw.annotations ?? [];
      const order = rawAnns.map((a: any) => {
        const types: string[] = a.type ?? [];
        return (types[0] ?? "").replace("AnnotationType_", "");
      }).filter((t: string) => t.length > 0);
      if (order.length > 0) orderings.push(order);
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

    // Compute canonical annotation order + consistency
    const { annotationOrder, orderConsistency } = computeCanonicalOrder(orderings);

    slots.push({
      index: si + 1,
      role: instances[0]?.slots[si]?.role ?? "UNKNOWN",
      updateType: updateTypes,
      followedBy: followedBys,
      annotations: { always, sometimes },
      gsIdDelta: gsIdDeltas,
      annotationOrder,
      orderConsistency,
      fieldPresence,
      fieldTotal: total,
    });
  }

  return slots;
}

function computeCanonicalOrder(orderings: string[][]): { annotationOrder: string[]; orderConsistency: number } {
  if (orderings.length === 0) return { annotationOrder: [], orderConsistency: 1.0 };

  // Count exact sequence occurrences
  const seqCounts = new Map<string, { seq: string[]; count: number }>();
  for (const ord of orderings) {
    const key = ord.join(",");
    const entry = seqCounts.get(key) ?? { seq: ord, count: 0 };
    entry.count++;
    seqCounts.set(key, entry);
  }

  // Find most common
  let best: string[] = [];
  let bestCount = 0;
  for (const { seq, count } of seqCounts.values()) {
    if (count > bestCount) { best = seq; bestCount = count; }
  }

  return {
    annotationOrder: best,
    orderConsistency: orderings.length > 0 ? bestCount / orderings.length : 1.0,
  };
}

// --- Rendering ---

interface RenderOpts {
  showFieldPresence: boolean;
  showPersistent: boolean;
  showGsidGaps: boolean;
}

function renderHuman(aggregated: AggregatedInteraction[], opts: RenderOpts) {
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

      // Show annotation ordering for slots with 3+ annotation types
      if (slot.annotationOrder.length >= 3) {
        const orderStr = slot.annotationOrder.map(abbreviate).join(" \u2192 ");
        const pct = Math.round(slot.orderConsistency * 100);
        console.log(`      order: ${orderStr}  (${pct}% consistent)`);
      }

      // Field presence
      if (opts.showFieldPresence && slot.fieldTotal > 0) {
        const fp = slot.fieldPresence;
        const t = slot.fieldTotal;
        const pct = (n: number) => `${Math.round(((n ?? 0) / t) * 100)}%`.padStart(4);
        console.log(`      fields: gameInfo:${pct(fp.gameInfo)}  turnInfo:${pct(fp.turnInfo)}  players:${pct(fp.players)}`);
        console.log(`              zones:${pct(fp.zones)}  objects:${pct(fp.gameObjects)}  timers:${pct(fp.timers)}`);
        console.log(`              anns:${pct(fp.annotations)}  pAnns:${pct(fp.persistentAnns)}  actions:${pct(fp.actions)}`);
        console.log(`              delIds:${pct(fp.diffDeleted)}  delPAnns:${pct(fp.diffDelPAnns)}  prevGsId:${pct(fp.prevGsId)}`);
      }
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

    // Persistent annotation lifecycle
    if (opts.showPersistent) {
      const lifecycle = analyzePersistentLifecycle(agg);
      if (lifecycle.length > 0) {
        console.log(`\n  Persistent annotation lifecycle:`);
        for (const pa of lifecycle) {
          const appearsStr = `slot ${pa.appearsSlot} (${agg.slots[pa.appearsSlot - 1]?.role ?? "?"})`;
          const removedStr = pa.removedSlot != null
            ? `slot ${pa.removedSlot} (${agg.slots[pa.removedSlot - 1]?.role ?? "?"})`
            : "never (persists)";
          const pct = Math.round(pa.frequency * 100);
          console.log(`    ${pa.type}:`);
          console.log(`      appears: ${appearsStr}  ${pct}%`);
          console.log(`      removed: ${removedStr}`);
        }
      }
    }

    // gsId gap analysis
    if (opts.showGsidGaps && agg.slots.length > 1) {
      const gaps = analyzeGsidGaps(agg);
      if (gaps.length > 0) {
        console.log(`\n  Gap analysis:`);
        for (const gap of gaps) {
          console.log(`    slot ${gap.fromSlot}\u2192${gap.toSlot} (${gap.fromRole} \u2192 ${gap.toRole}):`);
          const deltaParts = Object.entries(gap.deltas)
            .sort(([, a], [, b]) => b - a)
            .map(([d, n]) => `${d} (${Math.round((n / gap.total) * 100)}%)`);
          console.log(`      gsId delta: ${deltaParts.join(", ")}`);
          if (Object.keys(gap.gapRoles).length > 0) {
            const roleParts = Object.entries(gap.gapRoles)
              .sort(([, a], [, b]) => b - a)
              .map(([r, n]) => `${r} (${Math.round((n / gap.gapCount) * 100)}%)`);
            console.log(`      gap contents: ${roleParts.join(", ")}`);
          }
        }
      }
    }
  }
}

// --- Persistent annotation lifecycle ---

interface PersistentLifecycleEntry {
  type: string;
  appearsSlot: number;
  removedSlot: number | null;
  frequency: number; // fraction of instances where this appears
}

function analyzePersistentLifecycle(agg: AggregatedInteraction): PersistentLifecycleEntry[] {
  const typeData = new Map<string, { appears: Map<number, number>; removed: Map<number | null, number>; total: number }>();

  for (const inst of agg.instances) {
    const seen = new Set<string>();
    const firstSeen = new Map<string, number>();
    const deleted = new Map<string, number>();

    for (let si = 0; si < inst.slots.length; si++) {
      const raw = inst.slots[si].gsm.raw;

      // Collect pAnn types in this GSM
      for (const pAnn of raw.persistentAnnotations ?? []) {
        for (const t of pAnn.type ?? []) {
          const stripped = t.replace("AnnotationType_", "");
          if (!seen.has(stripped)) {
            seen.add(stripped);
            firstSeen.set(stripped, si + 1);
          }
        }
      }

      // Check deletions
      const deletedIds = new Set(raw.diffDeletedPersistentAnnotationIds ?? []);
      if (deletedIds.size > 0) {
        // We need to know which types were deleted — check if we can match by ID
        // Since we don't track pAnn IDs to types, mark any type last seen before this slot as deleted here
        // Simpler: record that deletions happened at this slot
        for (const pAnnType of seen) {
          if (!deleted.has(pAnnType)) {
            // Check if this type's pAnn was among deleted — heuristic: if we saw it but it's gone
            // This is imprecise; better to just track which types disappear
          }
        }
      }
    }

    // For each type seen, record first/last
    for (const [type, slot] of firstSeen) {
      let data = typeData.get(type);
      if (!data) { data = { appears: new Map(), removed: new Map(), total: 0 }; typeData.set(type, data); }
      data.total++;
      data.appears.set(slot, (data.appears.get(slot) ?? 0) + 1);
      // Check if any deletion happened — for now, null means persists
      data.removed.set(null, (data.removed.get(null) ?? 0) + 1);
    }
  }

  // Build results — only types that appear in >20% of instances
  const results: PersistentLifecycleEntry[] = [];
  const minFreq = agg.instances.length * 0.2;
  for (const [type, data] of typeData) {
    if (data.total < minFreq) continue;
    // Most common appear slot
    let bestSlot = 1, bestCount = 0;
    for (const [slot, count] of data.appears) {
      if (count > bestCount) { bestSlot = slot; bestCount = count; }
    }
    // Most common removed slot
    let bestRemoved: number | null = null, bestRemovedCount = 0;
    for (const [slot, count] of data.removed) {
      if (count > bestRemovedCount) { bestRemoved = slot; bestRemovedCount = count; }
    }
    results.push({
      type,
      appearsSlot: bestSlot,
      removedSlot: bestRemoved,
      frequency: data.total / agg.instances.length,
    });
  }
  return results.sort((a, b) => a.appearsSlot - b.appearsSlot);
}

// --- gsId gap analysis ---

interface GapAnalysis {
  fromSlot: number;
  toSlot: number;
  fromRole: string;
  toRole: string;
  deltas: Record<string, number>; // delta value → count
  gapRoles: Record<string, number>; // role of intervening GSMs → count
  gapCount: number; // total intervening GSMs
  total: number; // total instances
}

function analyzeGsidGaps(agg: AggregatedInteraction): GapAnalysis[] {
  const results: GapAnalysis[] = [];
  const slotCount = agg.canonical.length;

  for (let si = 0; si < slotCount - 1; si++) {
    const deltas: Record<string, number> = {};
    const gapRoles: Record<string, number> = {};
    let gapCount = 0;
    let total = 0;

    for (const inst of agg.instances) {
      if (si + 1 >= inst.slots.length) continue;
      const fromGsId = inst.slots[si].gsm.gsId;
      const toGsId = inst.slots[si + 1].gsm.gsId;
      const delta = toGsId - fromGsId;
      total++;

      const bucket = delta >= 4 ? "4+" : String(delta);
      deltas[bucket] = (deltas[bucket] ?? 0) + 1;

      // Classify intervening GSMs
      if (delta > 1) {
        for (const gsm of inst.game.greMessages) {
          if (gsm.gsId > fromGsId && gsm.gsId < toGsId) {
            const role = classifyGsm(gsm);
            gapRoles[role] = (gapRoles[role] ?? 0) + 1;
            gapCount++;
          }
        }
      }
    }

    if (total > 0) {
      results.push({
        fromSlot: si + 1,
        toSlot: si + 2,
        fromRole: agg.canonical[si],
        toRole: agg.canonical[si + 1],
        deltas,
        gapRoles,
        gapCount,
        total,
      });
    }
  }
  return results;
}

// --- Diff rendering ---

function renderDiff(
  left: AggregatedInteraction[],
  right: AggregatedInteraction[],
  leftSrc: string,
  rightSrc: string,
) {
  const allTypes = new Set([...left.map((a) => a.type), ...right.map((a) => a.type)]);

  for (const type of allTypes) {
    const l = left.find((a) => a.type === type);
    const r = right.find((a) => a.type === type);
    const lCount = l?.instances.length ?? 0;
    const rCount = r?.instances.length ?? 0;

    console.log(`\n${type}`);
    console.log(`${"".padEnd(20)} ${leftSrc.padEnd(35)} ${rightSrc}`);
    console.log(`  instances         ${String(lCount).padEnd(35)} ${rCount}`);
    console.log(`  slots             ${String(l?.canonical.length ?? 0).padEnd(35)} ${r?.canonical.length ?? 0}`);

    const maxSlots = Math.max(l?.slots.length ?? 0, r?.slots.length ?? 0);
    for (let si = 0; si < maxSlots; si++) {
      const ls = l?.slots[si];
      const rs = r?.slots[si];
      console.log(`  [${si + 1}] role          ${(ls?.role ?? "\u2014").padEnd(35)} ${rs?.role ?? "\u2014"}`);
      console.log(`      updateType    ${(ls ? topValue(ls.updateType) : "\u2014").padEnd(35)} ${rs ? topValue(rs.updateType) : "\u2014"}`);

      const lAnns = ls ? [...ls.annotations.always, ...Object.keys(ls.annotations.sometimes).map((a) => `${a}?`)].map(abbreviate).join(" ") : "\u2014";
      const rAnns = rs ? [...rs.annotations.always, ...Object.keys(rs.annotations.sometimes).map((a) => `${a}?`)].map(abbreviate).join(" ") : "\u2014";
      console.log(`      annotations   [${lAnns}]`.padEnd(56) + `[${rAnns}]`);

      if (ls?.annotationOrder.length && ls.annotationOrder.length >= 3) {
        const lOrd = ls.annotationOrder.map(abbreviate).join(" \u2192 ");
        const rOrd = rs?.annotationOrder.length ? rs.annotationOrder.map(abbreviate).join(" \u2192 ") : "\u2014";
        const lPct = Math.round((ls?.orderConsistency ?? 0) * 100);
        const rPct = rs ? Math.round(rs.orderConsistency * 100) : 0;
        console.log(`      order         ${lOrd} (${lPct}%)`.padEnd(56) + `${rOrd} (${rPct}%)`);
      }
    }

    // Auto-detect gaps
    const gaps: string[] = [];
    const lSlotCount = l?.canonical.length ?? 0;
    const rSlotCount = r?.canonical.length ?? 0;
    if (lSlotCount !== rSlotCount) {
      gaps.push(`slot count: ${leftSrc}=${lSlotCount} vs ${rightSrc}=${rSlotCount}`);
    }
    for (let si = 0; si < Math.min(lSlotCount, rSlotCount); si++) {
      const ls = l?.slots[si];
      const rs = r?.slots[si];
      if (ls && rs) {
        // Annotation always-set diff
        const lAlways = new Set(ls.annotations.always);
        const rAlways = new Set(rs.annotations.always);
        const extra = [...rAlways].filter((a) => !lAlways.has(a));
        const missing = [...lAlways].filter((a) => !rAlways.has(a));
        if (extra.length > 0) gaps.push(`slot ${si + 1}: ${rightSrc} has extra always-present: ${extra.map(abbreviate).join(", ")}`);
        if (missing.length > 0) gaps.push(`slot ${si + 1}: ${rightSrc} missing always-present: ${missing.map(abbreviate).join(", ")}`);

        // updateType mismatch
        const lUt = topValue(ls.updateType);
        const rUt = topValue(rs.updateType);
        if (lUt !== rUt) gaps.push(`slot ${si + 1}: updateType ${leftSrc}=${lUt} vs ${rightSrc}=${rUt}`);

        // Order consistency delta
        const delta = Math.abs(ls.orderConsistency - rs.orderConsistency);
        if (delta > 0.2) {
          gaps.push(`slot ${si + 1}: order consistency ${Math.round(ls.orderConsistency * 100)}% vs ${Math.round(rs.orderConsistency * 100)}%`);
        }
      }
    }
    if (gaps.length > 0) {
      console.log(`\n  Gaps:`);
      for (const g of gaps) console.log(`    - ${g}`);
    }
  }
}

function renderDiffJson(
  left: AggregatedInteraction[],
  right: AggregatedInteraction[],
  leftSrc: string,
  rightSrc: string,
  leftGames: number,
  rightGames: number,
) {
  const allTypes = new Set([...left.map((a) => a.type), ...right.map((a) => a.type)]);
  const interactions: any[] = [];

  for (const type of allTypes) {
    const l = left.find((a) => a.type === type);
    const r = right.find((a) => a.type === type);
    interactions.push({
      type,
      left: l ? { instanceCount: l.instances.length, gameCount: l.gameCount, canonical: l.canonical, slots: l.slots } : null,
      right: r ? { instanceCount: r.instances.length, gameCount: r.gameCount, canonical: r.canonical, slots: r.slots } : null,
    });
  }

  console.log(JSON.stringify({
    diff: { left: leftSrc, right: rightSrc },
    interactions,
    meta: { leftGamesScanned: leftGames, rightGamesScanned: rightGames },
  }, null, 2));
}

function renderJson(aggregated: AggregatedInteraction[], gamesScanned: number, opts: RenderOpts) {
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
        annotationOrder: slot.annotationOrder,
        orderConsistency: slot.orderConsistency,
        fieldPresence: Object.fromEntries(
          Object.entries(slot.fieldPresence).map(([k, v]) => [k, slot.fieldTotal > 0 ? v / slot.fieldTotal : 0])
        ),
      })),
      ...(opts.showPersistent ? { persistent: analyzePersistentLifecycle(agg) } : {}),
      ...(opts.showGsidGaps ? { gsidGaps: analyzeGsidGaps(agg) } : {}),
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

function parseDiffFlag(args: string[]): [string, string] | null {
  for (let i = 0; i < args.length; i++) {
    if (args[i] === "--diff" && i + 2 < args.length) {
      return [args[i + 1], args[i + 2]];
    }
  }
  return null;
}
