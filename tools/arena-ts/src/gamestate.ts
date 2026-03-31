// src/gamestate.ts
// Live game state from Player.log via scry-ts accumulator.
// Provides hand, actions, turn info, zones — everything needed for gameplay commands.

import { parseLog } from "../../scry-ts/src/parser";
import { detectGames } from "../../scry-ts/src/games";
import { Accumulator, type GameObject } from "../../scry-ts/src/accumulator";
import { getResolver } from "../../scry-ts/src/cards";
import { DEFAULT_LOG } from "../../scry-ts/src/log";
import { existsSync } from "fs";
import { stripPrefix } from "../../scry-ts/src/format";

export interface TurnInfo {
  turn: number;
  phase: string;
  step: string;
  activePlayer: number;
  priorityPlayer: number;
  isOurTurn: boolean;
}

export interface HandCard {
  instanceId: number;
  grpId: number;
  name: string;
  index: number;    // position in hand (0-based)
}

export interface PlayableAction {
  type: string;     // "Play" | "Cast" | "Activate" | etc
  instanceId: number;
  grpId: number;
  name: string;
  manaCost?: string;
}

export interface LiveState {
  turnInfo: TurnInfo;
  hand: HandCard[];
  lands: PlayableAction[];     // ActionType_Play with no mana cost
  casts: PlayableAction[];     // ActionType_Cast
  otherActions: PlayableAction[];
  ourLife: number;
  oppLife: number;
  handCount: number;
  gameStateId: number;
}

/** Get accumulated game state for the active game. Returns null if no game active. */
export async function liveState(): Promise<LiveState | null> {
  if (!existsSync(DEFAULT_LOG)) return null;

  const text = await Bun.file(DEFAULT_LOG).text();
  const events = [...parseLog(text.split("\n"))];
  const games = detectGames(events);

  // Find the last game (active or just finished)
  const game = games[games.length - 1];
  if (!game) return null;

  // Replay through accumulator
  const acc = new Accumulator();
  for (const gsm of game.greMessages) {
    acc.apply(gsm.raw);
  }

  if (!acc.current) return null;

  const state = acc.current;
  const resolver = getResolver();
  const ti = state.turnInfo;

  // Turn info
  const turnInfo: TurnInfo = {
    turn: ti?.turnNumber ?? 0,
    phase: stripPrefix(ti?.phase ?? "", "Phase_"),
    step: stripPrefix(ti?.step ?? "", "Step_"),
    activePlayer: ti?.activePlayer ?? 0,
    priorityPlayer: ti?.priorityPlayer ?? 0,
    isOurTurn: ti?.activePlayer === game.ourSeat,
  };

  // Find hand zone for our seat
  const ourSeat = game.ourSeat;
  const handCards: HandCard[] = [];
  let handZoneId: number | null = null;
  for (const [, z] of state.zones) {
    if (z.type === "ZoneType_Hand" && z.ownerSeatId === ourSeat) {
      handZoneId = z.zoneId;
      break;
    }
  }

  if (handZoneId != null) {
    let idx = 0;
    for (const [, obj] of state.objects) {
      if (obj.zoneId !== handZoneId) continue;
      const name = resolver?.resolve(obj.grpId) ?? `grp=${obj.grpId}`;
      handCards.push({
        instanceId: obj.instanceId,
        grpId: obj.grpId,
        name,
        index: idx++,
      });
    }
  }

  // Actions (our seat only)
  const lands: PlayableAction[] = [];
  const casts: PlayableAction[] = [];
  const otherActions: PlayableAction[] = [];
  const handIds = new Set(handCards.map(c => c.instanceId));

  for (const a of state.actions) {
    if (a.seatId != null && a.seatId !== ourSeat) continue;
    const action = (a as any).action ?? a;
    const atype = action.actionType ?? "";
    if (atype.includes("Activate_Mana")) continue;

    const grpId = action.grpId ?? 0;
    const iid = action.instanceId ?? 0;
    let name = grpId ? resolver?.resolve(grpId) ?? null : null;
    if (!name && iid) {
      const obj = acc.findObject(iid);
      if (obj) name = resolver?.resolve(obj.grpId) ?? null;
    }

    const pa: PlayableAction = {
      type: stripPrefix(atype, "ActionType_"),
      instanceId: iid,
      grpId,
      name: name ?? `grp=${grpId}`,
    };

    if (atype === "ActionType_Play" && handIds.has(iid)) {
      lands.push(pa);
    } else if (atype === "ActionType_Cast") {
      casts.push(pa);
    } else {
      otherActions.push(pa);
    }
  }

  // Life totals
  const oppSeat = ourSeat === 1 ? 2 : 1;
  const p1 = state.players.get(ourSeat);
  const p2 = state.players.get(oppSeat);

  return {
    turnInfo,
    hand: handCards,
    lands,
    casts,
    otherActions,
    ourLife: p1?.lifeTotal ?? 20,
    oppLife: p2?.lifeTotal ?? 20,
    handCount: handCards.length,
    gameStateId: state.gameStateId ?? 0,
  };
}
