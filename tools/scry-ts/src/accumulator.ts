/**
 * Game state accumulator — applies Full and Diff GSM updates
 * to maintain a materialized current game state.
 *
 * Design decisions:
 * - Object merge is whole-object replace (proto3 semantics:
 *   absent fields = default, not "unchanged")
 * - Zones merge by zoneId replacement (complete snapshots)
 * - Annotations/actions are ephemeral (diff's values only)
 * - Persistent annotations accumulated across GSMs, deleted via
 *   diffDeletedPersistentAnnotationIds
 * - ObjectIdChanged tracking: maintains id chain so cards can be
 *   traced across zone transfers even when instanceId changes
 */

export interface GameState {
  gameStateId: number;
  type: string;
  turnInfo: TurnInfo | null;
  players: Map<number, Player>;
  zones: Map<number, Zone>;
  objects: Map<number, GameObject>;
  annotations: any[];
  persistentAnnotations: Map<number, any>;
  actions: any[];
  gameInfo: any | null;
}

export interface TurnInfo {
  turnNumber: number;
  phase: string;
  step: string;
  activePlayer: number;
  priorityPlayer: number;
  decisionPlayer: number;
}

export interface Player {
  seatNumber: number;
  lifeTotal: number;
  maxHandSize: number;
  teamId: number;
  status: string | null;
  pendingMessageType: string | null;
}

export interface Zone {
  zoneId: number;
  type: string;
  visibility: string;
  ownerSeatId: number | null;
  objectIds: number[];
}

export interface GameObject {
  instanceId: number;
  grpId: number;
  type: string;
  zoneId: number;
  ownerSeatId: number;
  controllerSeatId: number;
  cardTypes: string[];
  subtypes: string[];
  colors: string[];
  power: number | null;
  toughness: number | null;
  isTapped: boolean;
  hasSummoningSickness: boolean;
  damage: number;
  loyalty: number | null;
  attackState: string;
  blockState: string;
  visibility: string;
  overlayGrpId: number;
  objectSourceGrpId: number;
  parentId: number;
  isFacedown: boolean;
  /** Arena localization ID — not a card name. Resolve via grpId → CardResolver. */
  name: number;
}

const MAX_HISTORY = 8;

export class Accumulator {
  current: GameState | null = null;

  /** ObjectIdChanged chain: old instanceId → new instanceId. */
  private idForward = new Map<number, number>();
  /** Reverse: new instanceId → old instanceId. */
  private idBackward = new Map<number, number>();

  private history = new Map<number, GameState>();
  private historyOrder: number[] = [];

  /** Apply a raw GSM (from Player.log JSON). */
  apply(rawGsm: any): void {
    const gs = parseGameState(rawGsm);
    const type = rawGsm.type ?? "";

    let merged: GameState;
    if (type.includes("Full") || !this.current) {
      merged = deepCloneState(gs);
      this.idForward.clear();
      this.idBackward.clear();
    } else {
      merged = this.mergeDiff(this.current, gs, rawGsm);
    }

    // Process persistent annotations
    // On Full, persistent annotations start empty (parseGameState initializes
    // an empty Map). The clear() is a safety net — a no-op in normal flow
    // but guards against future changes to parseGameState.
    if (type.includes("Full")) {
      merged.persistentAnnotations.clear();
    } else {
      // Remove deleted persistent annotations
      for (const id of rawGsm.diffDeletedPersistentAnnotationIds ?? []) {
        merged.persistentAnnotations.delete(id);
      }
    }
    // Add new persistent annotations
    for (const ann of rawGsm.persistentAnnotations ?? []) {
      if (ann.id != null) {
        merged.persistentAnnotations.set(ann.id, ann);
      }
    }

    // Track ObjectIdChanged from annotations
    this.processIdChanges(gs.annotations);

    this.current = merged;
    this.record(merged);
  }

  /** Get accumulated state at a specific gsId (from history). */
  getState(gsId: number): GameState | null {
    return this.history.get(gsId) ?? null;
  }

  /** Resolve an instanceId to its latest ID following the chain. */
  resolveId(instanceId: number): number {
    let current = instanceId;
    const seen = new Set<number>();
    while (this.idForward.has(current)) {
      if (seen.has(current)) break; // cycle guard
      seen.add(current);
      current = this.idForward.get(current)!;
    }
    return current;
  }

  /** Trace an instanceId back to its original. */
  traceBack(instanceId: number): number[] {
    const chain = [instanceId];
    let current = instanceId;
    const seen = new Set<number>();
    while (this.idBackward.has(current)) {
      if (seen.has(current)) break;
      seen.add(current);
      current = this.idBackward.get(current)!;
      chain.unshift(current);
    }
    return chain;
  }

  /** All ID chain entries (for debugging). */
  get idChain(): Map<number, number> {
    return new Map(this.idForward);
  }

  /** Find a game object by instanceId, following ID chain if needed. */
  findObject(instanceId: number): GameObject | null {
    if (!this.current) return null;
    const resolved = this.resolveId(instanceId);
    return this.current.objects.get(resolved) ?? null;
  }

  /** Get all objects in a specific zone type. */
  objectsInZone(zoneType: string, ownerSeat?: number): GameObject[] {
    if (!this.current) return [];
    const zoneIds = new Set<number>();
    for (const [, zone] of this.current.zones) {
      if (zone.type.includes(zoneType)) {
        if (ownerSeat != null && zone.ownerSeatId !== ownerSeat) continue;
        zoneIds.add(zone.zoneId);
      }
    }
    const result: GameObject[] = [];
    for (const [, obj] of this.current.objects) {
      if (zoneIds.has(obj.zoneId)) result.push(obj);
    }
    return result;
  }

  reset(): void {
    this.current = null;
    this.idForward.clear();
    this.idBackward.clear();
    this.history.clear();
    this.historyOrder = [];
  }

  // --- Internal ---

  private mergeDiff(base: GameState, diff: GameState, rawDiff: any): GameState {
    // turnInfo: diff replaces if present
    const turnInfo = diff.turnInfo ?? structuredClone(base.turnInfo);

    // players: merge by seat
    const players = new Map(base.players);
    for (const [seat, player] of diff.players) {
      players.set(seat, player);
    }

    // zones: merge by zoneId (diff zone = complete snapshot)
    const zones = new Map(base.zones);
    for (const [zoneId, zone] of diff.zones) {
      zones.set(zoneId, zone);
    }

    // objects: diff object replaces entire object (proto3 semantics)
    const objects = new Map(base.objects);
    for (const [iid, obj] of diff.objects) {
      objects.set(iid, obj);
    }
    // deletions
    for (const iid of rawDiff.diffDeletedInstanceIds ?? []) {
      objects.delete(iid);
    }

    // annotations + actions: ephemeral, use diff's values
    const annotations = [...diff.annotations];
    const actions = [...diff.actions];

    // gameInfo: diff replaces if present
    const gameInfo = diff.gameInfo ?? structuredClone(base.gameInfo);

    // persistent annotations: carried from base, updated by caller
    const persistentAnnotations = new Map(base.persistentAnnotations);

    return {
      gameStateId: diff.gameStateId,
      type: diff.type,
      turnInfo,
      players,
      zones,
      objects,
      annotations,
      persistentAnnotations,
      actions,
      gameInfo,
    };
  }

  private processIdChanges(annotations: any[]): void {
    for (const ann of annotations) {
      const types: string[] = ann.type ?? [];
      if (!types.some((t: string) => t.includes("ObjectIdChanged"))) continue;

      const details = ann.details ?? [];
      let origId: number | null = null;
      let newId: number | null = null;
      for (const d of details) {
        if (d.key === "orig_id") {
          origId = d.valueInt32?.[0] ?? d.valueUint32?.[0] ?? null;
        }
        if (d.key === "new_id") {
          newId = d.valueInt32?.[0] ?? d.valueUint32?.[0] ?? null;
        }
      }
      if (origId != null && newId != null) {
        this.idForward.set(origId, newId);
        this.idBackward.set(newId, origId);
      }
    }
  }

  private record(state: GameState): void {
    const gsId = state.gameStateId;
    if (this.history.has(gsId)) {
      // Move to end
      this.historyOrder = this.historyOrder.filter((id) => id !== gsId);
    }
    this.history.set(gsId, deepCloneState(state));
    this.historyOrder.push(gsId);

    while (this.historyOrder.length > MAX_HISTORY) {
      const oldest = this.historyOrder.shift()!;
      this.history.delete(oldest);
    }
  }
}

// --- Parsing raw GSM JSON → typed structures ---

function parseGameState(raw: any): GameState {
  const ti = raw.turnInfo;
  const turnInfo: TurnInfo | null = ti
    ? {
        turnNumber: ti.turnNumber ?? 0,
        phase: ti.phase ?? "",
        step: ti.step ?? "",
        activePlayer: ti.activePlayer ?? 0,
        priorityPlayer: ti.priorityPlayer ?? 0,
        decisionPlayer: ti.decisionPlayer ?? 0,
      }
    : null;

  const players = new Map<number, Player>();
  for (const p of raw.players ?? []) {
    const seat = p.systemSeatNumber ?? 0;
    players.set(seat, {
      seatNumber: seat,
      lifeTotal: p.lifeTotal ?? 0,
      maxHandSize: p.maxHandSize ?? 0,
      teamId: p.teamId ?? 0,
      status: p.status ?? null,
      pendingMessageType: p.pendingMessageType ?? null,
    });
  }

  const zones = new Map<number, Zone>();
  for (const z of raw.zones ?? []) {
    zones.set(z.zoneId, {
      zoneId: z.zoneId,
      type: z.type ?? "",
      visibility: z.visibility ?? "",
      ownerSeatId: z.ownerSeatId ?? null,
      objectIds: [...(z.objectInstanceIds ?? [])],
    });
  }

  const objects = new Map<number, GameObject>();
  for (const o of raw.gameObjects ?? []) {
    objects.set(o.instanceId, parseObject(o));
  }

  return {
    gameStateId: raw.gameStateId ?? 0,
    type: raw.type ?? "",
    turnInfo,
    players,
    zones,
    objects,
    annotations: [...(raw.annotations ?? [])],
    persistentAnnotations: new Map(),
    actions: [...(raw.actions ?? [])],
    gameInfo: raw.gameInfo ?? null,
  };
}

function parseObject(o: any): GameObject {
  return {
    instanceId: o.instanceId ?? 0,
    grpId: o.grpId ?? 0,
    type: o.type ?? "",
    zoneId: o.zoneId ?? 0,
    ownerSeatId: o.ownerSeatId ?? 0,
    controllerSeatId: o.controllerSeatId ?? 0,
    cardTypes: [...(o.cardTypes ?? [])],
    subtypes: [...(o.subtypes ?? [])],
    colors: [...(o.color ?? [])],
    power: o.power?.value ?? null,
    toughness: o.toughness?.value ?? null,
    isTapped: o.isTapped ?? false,
    hasSummoningSickness: o.hasSummoningSickness ?? false,
    damage: o.damage ?? 0,
    loyalty: o.loyalty?.value ?? null,
    attackState: o.attackState ?? "",
    blockState: o.blockState ?? "",
    visibility: o.visibility ?? "",
    overlayGrpId: o.overlayGrpId ?? 0,
    objectSourceGrpId: o.objectSourceGrpId ?? 0,
    parentId: o.parentId ?? 0,
    isFacedown: o.isFacedown ?? false,
    name: o.name ?? 0,
  };
}

function deepCloneState(s: GameState): GameState {
  return {
    gameStateId: s.gameStateId,
    type: s.type,
    turnInfo: s.turnInfo ? { ...s.turnInfo } : null,
    players: new Map([...s.players].map(([k, v]) => [k, { ...v }])),
    zones: new Map([...s.zones].map(([k, v]) => [k, { ...v, objectIds: [...v.objectIds] }])),
    objects: new Map([...s.objects].map(([k, v]) => [k, { ...v, cardTypes: [...v.cardTypes], subtypes: [...v.subtypes], colors: [...v.colors] }])),
    annotations: [...s.annotations],
    persistentAnnotations: new Map(s.persistentAnnotations),
    actions: [...s.actions],
    gameInfo: s.gameInfo ? structuredClone(s.gameInfo) : null,
  };
}
