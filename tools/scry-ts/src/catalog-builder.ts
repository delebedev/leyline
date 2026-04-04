/**
 * Catalog builder — data collection & profile building for `scry catalog`.
 *
 * Collects annotations, prompts, and actions from the saved game corpus
 * (~/.scry/games/) and builds typed profiles with key frequency, value
 * samples, persistence, co-types, card attribution, and date ranges.
 */

import { loadCatalog, readSavedGame } from "./catalog";
import { loadMeta } from "./meta";
import { parseSavedSourceFilter, matchesSource, type GameSource } from "./provenance";
import { parseLog } from "./parser";
import { detectGames, type GsmSummary, type Game } from "./games";
import { type CardResolver } from "./cards";
import { stripPrefix } from "./format";

// --- Types ---

export interface AnnotationInstance {
  type: string;
  keys: string[];
  values: Record<string, string | number>;
  isPersistent: boolean;
  gameLabel: string;
  gsmId: number;
  affectorId: number | undefined;
  affectedIds: number[];
  coTypes: string[];
}

export interface PromptInstance {
  type: string;
  gameLabel: string;
  fields: string[];
  raw: unknown;
}

export interface ActionInstance {
  type: string;
  gameLabel: string;
  fields: string[];
  grpId: number | undefined;
  instanceId: number | undefined;
  raw: unknown;
}

export interface CardSummary {
  count: number;
  notable: { grp: number; name: string; context: string }[];
}

export interface AnnotationProfile {
  type: string;
  instances: number;
  games: number;
  persistence: "transient" | "persistent" | "mixed";
  alwaysKeys: string[];
  sometimesKeys: { key: string; pct: number }[];
  valueSamples: Record<string, Set<string>>;
  cards: CardSummary;
  coTypes: Map<string, number>;
  firstSeen: string | null;
  lastSeen: string | null;
}

export interface PromptProfile {
  type: string;
  occurrences: number;
  games: number;
  alwaysFields: string[];
  sometimesFields: { field: string; pct: number }[];
  firstSeen: string | null;
  lastSeen: string | null;
}

export interface ActionProfile {
  type: string;
  occurrences: number;
  games: number;
  alwaysFields: string[];
  sometimesFields: { field: string; pct: number }[];
  firstSeen: string | null;
  lastSeen: string | null;
}

export interface CorpusData {
  annotations: Map<string, AnnotationInstance[]>;
  prompts: Map<string, PromptInstance[]>;
  actions: Map<string, ActionInstance[]>;
  gameCount: number;
  gsmCount: number;
}

// --- Prompt types we track ---

const PROMPT_GRE_TYPES = new Set([
  "GREMessageType_ActionsAvailableReq",
  "GREMessageType_SelectTargetsReq",
  "GREMessageType_SelectNReq",
  "GREMessageType_DeclareAttackersReq",
  "GREMessageType_DeclareBlockersReq",
  "GREMessageType_OptionalActionMessage",
  "GREMessageType_SearchReq",
  "GREMessageType_PromptReq",
  "GREMessageType_MulliganReq",
  "GREMessageType_ChooseStartingPlayerReq",
  "GREMessageType_GroupReq",
  "GREMessageType_OrderReq",
  "GREMessageType_OrderDamageConfirmation",
  "GREMessageType_SelectReplacementReq",
  "GREMessageType_IntermissionReq",
  "GREMessageType_CastingTimeOptionsReq",
]);

// --- Profile builders ---

export function buildAnnotationProfile(
  type: string,
  instances: AnnotationInstance[],
): AnnotationProfile {
  const total = instances.length;
  const gameLabels = new Set(instances.map((i) => i.gameLabel));

  // Key frequency
  const keyCounts = new Map<string, number>();
  for (const inst of instances) {
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
  for (const inst of instances) {
    for (const [key, val] of Object.entries(inst.values)) {
      if (!valueSamples[key]) valueSamples[key] = new Set();
      if (valueSamples[key].size < 10) valueSamples[key].add(String(val));
    }
  }

  // Persistence classification
  let persistentCount = 0;
  let transientCount = 0;
  for (const inst of instances) {
    if (inst.isPersistent) persistentCount++;
    else transientCount++;
  }
  const persistence: "transient" | "persistent" | "mixed" =
    persistentCount === 0 ? "transient" :
    transientCount === 0 ? "persistent" :
    "mixed";

  // Co-types (count unique GSMs where both types co-occur)
  const coTypeGsms = new Map<string, Set<number>>();
  for (const inst of instances) {
    for (const ct of inst.coTypes) {
      let seen = coTypeGsms.get(ct);
      if (!seen) { seen = new Set(); coTypeGsms.set(ct, seen); }
      seen.add(inst.gsmId);
    }
  }
  const uniqueGsmIds = new Set(instances.map((i) => i.gsmId));
  const gsmCount = uniqueGsmIds.size;
  // Filter to >50% GSM co-occurrence
  const coTypes = new Map<string, number>();
  for (const [ct, gsms] of coTypeGsms) {
    const pct = gsms.size / gsmCount;
    if (pct > 0.5) coTypes.set(ct, gsms.size);
  }

  // Card count proxy: unique affectorIds
  const affectorIds = new Set<number>();
  for (const inst of instances) {
    if (inst.affectorId != null) affectorIds.add(inst.affectorId);
  }

  // Date range from game labels (sorted lexicographically — labels are date-based)
  const sortedLabels = [...gameLabels].sort();
  const firstSeen = sortedLabels[0] ?? null;
  const lastSeen = sortedLabels[sortedLabels.length - 1] ?? null;

  return {
    type,
    instances: total,
    games: gameLabels.size,
    persistence,
    alwaysKeys,
    sometimesKeys,
    valueSamples,
    cards: { count: affectorIds.size, notable: [] },
    coTypes,
    firstSeen,
    lastSeen,
  };
}

export function buildPromptProfile(
  type: string,
  instances: PromptInstance[],
): PromptProfile {
  const total = instances.length;
  const gameLabels = new Set(instances.map((i) => i.gameLabel));

  const fieldCounts = new Map<string, number>();
  for (const inst of instances) {
    for (const field of inst.fields) {
      fieldCounts.set(field, (fieldCounts.get(field) ?? 0) + 1);
    }
  }

  const alwaysFields: string[] = [];
  const sometimesFields: { field: string; pct: number }[] = [];
  for (const [field, count] of [...fieldCounts.entries()].sort((a, b) => b[1] - a[1])) {
    if (count === total) {
      alwaysFields.push(field);
    } else {
      sometimesFields.push({ field, pct: Math.round((count / total) * 100) });
    }
  }

  const sortedLabels = [...gameLabels].sort();
  return {
    type,
    occurrences: total,
    games: gameLabels.size,
    alwaysFields,
    sometimesFields,
    firstSeen: sortedLabels[0] ?? null,
    lastSeen: sortedLabels[sortedLabels.length - 1] ?? null,
  };
}

export function buildActionProfile(
  type: string,
  instances: ActionInstance[],
): ActionProfile {
  const total = instances.length;
  const gameLabels = new Set(instances.map((i) => i.gameLabel));

  const fieldCounts = new Map<string, number>();
  for (const inst of instances) {
    for (const field of inst.fields) {
      fieldCounts.set(field, (fieldCounts.get(field) ?? 0) + 1);
    }
  }

  const alwaysFields: string[] = [];
  const sometimesFields: { field: string; pct: number }[] = [];
  for (const [field, count] of [...fieldCounts.entries()].sort((a, b) => b[1] - a[1])) {
    if (count === total) {
      alwaysFields.push(field);
    } else {
      sometimesFields.push({ field, pct: Math.round((count / total) * 100) });
    }
  }

  const sortedLabels = [...gameLabels].sort();
  return {
    type,
    occurrences: total,
    games: gameLabels.size,
    alwaysFields,
    sometimesFields,
    firstSeen: sortedLabels[0] ?? null,
    lastSeen: sortedLabels[sortedLabels.length - 1] ?? null,
  };
}

// --- Corpus collection ---

export async function collectCorpus(
  sourceFilter?: Set<GameSource>,
): Promise<CorpusData> {
  const catalog = loadCatalog();
  const allowedSources = sourceFilter ?? new Set<GameSource>(["real", "unknown"]);

  const annotations = new Map<string, AnnotationInstance[]>();
  const prompts = new Map<string, PromptInstance[]>();
  const actions = new Map<string, ActionInstance[]>();
  let gameCount = 0;
  let gsmCount = 0;

  for (const entry of catalog.games) {
    const meta = loadMeta(entry.file);
    if (!matchesSource(meta, allowedSources)) continue;

    const lines = readSavedGame(entry.file);
    const events = [...parseLog(lines)];
    const games = detectGames(events);

    for (const game of games) {
      gameCount++;
      const label = entry.file.replace(".log", "");

      // Collect annotations from GSMs
      for (const gsm of game.greMessages) {
        gsmCount++;
        collectAnnotationsFromGsm(gsm, label, annotations);
        collectActionsFromGsm(gsm, label, actions);
      }

      // Collect prompts from raw GRE stream
      collectPromptsFromGame(game, label, events, prompts);
    }
  }

  return { annotations, prompts, actions, gameCount, gsmCount };
}

function collectAnnotationsFromGsm(
  gsm: GsmSummary,
  gameLabel: string,
  out: Map<string, AnnotationInstance[]>,
): void {
  const rawAnns: any[] = gsm.raw.annotations ?? [];
  const rawPAnns: any[] = gsm.raw.persistentAnnotations ?? [];
  const allAnns = [...rawAnns, ...rawPAnns];
  if (allAnns.length === 0) return;

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

function collectActionsFromGsm(
  gsm: GsmSummary,
  gameLabel: string,
  out: Map<string, ActionInstance[]>,
): void {
  const rawActions: any[] = gsm.raw.actions ?? [];
  for (const act of rawActions) {
    // Inner action object holds the real payload
    const inner = act.action ?? act;
    const actionType = inner.actionType ?? act.actionType;
    if (!actionType) continue;

    const type = stripPrefix(actionType, "ActionType_");
    if (!type) continue;

    // Extract field names from inner action (excluding actionType itself)
    const fields = Object.keys(inner).filter((k) => k !== "actionType");

    const inst: ActionInstance = {
      type,
      gameLabel,
      fields,
      grpId: inner.grpId ?? act.grpId,
      instanceId: inner.instanceId ?? act.instanceId,
      raw: act,
    };

    const list = out.get(type) ?? [];
    list.push(inst);
    out.set(type, list);
  }
}

function collectPromptsFromGame(
  game: Game,
  gameLabel: string,
  events: any[],
  out: Map<string, PromptInstance[]>,
): void {
  // Walk the GRE stream for prompt-type messages
  for (const entry of game.greStream) {
    if (entry.kind !== "gre") continue;
    const fullType = `GREMessageType_${entry.type}`;
    if (!PROMPT_GRE_TYPES.has(fullType)) continue;

    const type = entry.type;

    // We don't have the raw payload in greStream — record with empty fields
    // The greStream only stores type/msgId/gameStateId. To get payload fields
    // we'd need to re-parse raw events. For now, we track occurrence + type.
    const inst: PromptInstance = {
      type,
      gameLabel,
      fields: [], // populated below if we can find the raw message
      raw: entry,
    };

    const list = out.get(type) ?? [];
    list.push(inst);
    out.set(type, list);
  }

  // Enrich prompt fields from raw events
  // The parser yields GRE events with full message payloads — walk those
  // and match by game timestamp window + message type
  for (const event of events) {
    if (event.type !== "gre") continue;
    for (const msg of event.messages as any[]) {
      const msgType = msg.type;
      if (!msgType || !PROMPT_GRE_TYPES.has(msgType)) continue;

      const type = stripPrefix(msgType, "GREMessageType_");
      // Extract top-level field names from the message payload (excluding 'type', 'msgId', 'systemSeatIds')
      const skipFields = new Set(["type", "msgId", "systemSeatIds"]);
      const fields = Object.keys(msg).filter((k) => !skipFields.has(k));

      const inst: PromptInstance = {
        type,
        gameLabel,
        fields,
        raw: msg,
      };

      const list = out.get(type) ?? [];
      list.push(inst);
      out.set(type, list);
    }
  }
}

// --- Card enrichment ---

export function enrichCards(
  profiles: Map<string, AnnotationProfile>,
  annotations: Map<string, AnnotationInstance[]>,
  resolver: CardResolver,
): void {
  for (const [type, profile] of profiles) {
    const instances = annotations.get(type) ?? [];
    const affectorCounts = new Map<number, number>();
    for (const inst of instances) {
      if (inst.affectorId != null) {
        affectorCounts.set(inst.affectorId, (affectorCounts.get(inst.affectorId) ?? 0) + 1);
      }
    }

    // Top affectors by frequency as notable cards
    const sorted = [...affectorCounts.entries()].sort((a, b) => b[1] - a[1]);
    const notable: { grp: number; name: string; context: string }[] = [];
    for (const [id, count] of sorted.slice(0, 5)) {
      const name = resolver.resolve(id);
      if (name) {
        notable.push({ grp: id, name, context: `${count} annotations` });
      }
    }

    profile.cards = {
      count: affectorCounts.size,
      notable,
    };
  }
}
