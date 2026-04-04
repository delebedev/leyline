import { resolveGame, parseGameFlag } from "../resolve";
import { Accumulator, type GameObject } from "../accumulator";
import { getResolver, resolveAbility, type CardResolver } from "../cards";
import { stripPrefix, zoneName, formatPhase } from "../format";
import type { GreMessageSummary } from "../games";

export async function boardCommand(args: string[]) {
  if (args[0] === "--help" || args[0] === "-h") {
    console.log("Usage: scry board [flags]\n");
    console.log("Show accumulated board state.\n");
    console.log("Flags:");
    console.log("  --game REF   Game reference (catalog filename or live index)");
    console.log("  --gsid N     Show state at specific gsId");
    console.log("  --json       Output raw accumulated state as JSON");
    return;
  }

  const { game, label } = await resolveGame(args);

  // Target gsId (optional — default: replay all)
  let targetGsId: number | null = null;
  for (let i = 0; i < args.length; i++) {
    if (args[i] === "--gsid" && i + 1 < args.length) {
      targetGsId = parseInt(args[++i], 10);
    }
  }

  const jsonMode = args.includes("--json");

  // Replay GSMs through accumulator
  const acc = new Accumulator();
  for (const gsm of game.greMessages) {
    acc.apply(gsm.raw);
    if (targetGsId != null && gsm.gsId === targetGsId) break;
  }

  if (!acc.current) {
    console.error("No game state accumulated");
    process.exit(1);
  }

  if (jsonMode) {
    const state = acc.current;
    console.log(JSON.stringify({
      gameStateId: state.gameStateId,
      turnInfo: state.turnInfo,
      players: Object.fromEntries(state.players),
      zones: Object.fromEntries(state.zones),
      objects: Object.fromEntries(state.objects),
      persistentAnnotations: Object.fromEntries(state.persistentAnnotations),
      annotations: state.annotations,
      actions: state.actions,
      gameInfo: state.gameInfo,
    }, null, 2));
    return;
  }

  const state = acc.current;
  const resolver = getResolver();
  const ti = state.turnInfo;

  // Header
  const phase = ti ? formatPhase(ti.phase, ti.step) : "—";
  const active = ti ? `seat ${ti.activePlayer} active` : "";
  const turn = ti?.turnNumber ?? 0;
  const p1 = state.players.get(1);
  const p2 = state.players.get(2);
  const life = `Life: ${p1?.lifeTotal ?? "?"}/${p2?.lifeTotal ?? "?"}`;

  console.log(`T${turn} ${phase}  ${active}  ${life}`);

  // Find last pending prompt from greStream at current gsId
  const currentGsId = state.gameStateId;
  let lastPrompt: GreMessageSummary | null = null;
  for (const entry of game.greStream) {
    if ("kind" in entry && entry.kind === "gre") {
      if (entry.gameStateId <= currentGsId) lastPrompt = entry;
    } else if ("gsId" in entry && entry.gsId > currentGsId) {
      break;
    }
  }
  if (lastPrompt && lastPrompt.type !== "ActionsAvailableReq") {
    console.log(`Pending: ${lastPrompt.type}`);
  }

  if (game.result && !game.active && targetGsId == null) {
    console.log(`\n=== Game over: ${game.result} ===`);
  }
  console.log("");

  // Build zone lookup
  const zoneById = new Map<number, { type: string; ownerSeatId: number | null }>();
  for (const [, z] of state.zones) {
    zoneById.set(z.zoneId, { type: z.type, ownerSeatId: z.ownerSeatId });
  }

  // Build set of zone-tracked objectIds (excludes phantom proxies like Adventure companions)
  const zoneTracked = new Set<number>();
  for (const [, z] of state.zones) {
    for (const id of z.objectIds) zoneTracked.add(id);
  }

  // Group objects by zone type + owner (skip phantom proxies not in any zone's objectIds)
  const grouped = new Map<string, GameObject[]>();
  for (const [, obj] of state.objects) {
    if (!zoneTracked.has(obj.instanceId)) continue;
    const zone = zoneById.get(obj.zoneId);
    const zt = zone ? zoneName(zone.type) : `zone=${obj.zoneId}`;
    const owner = zone?.ownerSeatId ?? obj.ownerSeatId;
    const key = `${zt}|${owner}`;
    if (!grouped.has(key)) grouped.set(key, []);
    grouped.get(key)!.push(obj);
  }

  // Print zones in logical order
  const zoneOrder = ["Battlefield", "Stack", "Hand", "Graveyard", "Exile", "Library", "Command", "Limbo"];

  const oppSeat = game.ourSeat === 1 ? 2 : 1;

  for (const zt of zoneOrder) {
    // Show our side first, then opponent
    for (const seat of [0, game.ourSeat, oppSeat]) {
      const key = `${zt}|${seat}`;
      const objects = grouped.get(key);
      if (!objects || objects.length === 0) continue;
      if (zt === "Limbo") continue;

      const seatLabel = seat === game.ourSeat ? " (you)" : seat === oppSeat ? " (opponent)" : "";

      if (zt === "Library") {
        console.log(`${zt}${seatLabel}: ${objects.length} cards`);
        continue;
      }

      if (zt === "Battlefield") {
        console.log(`${zt}${seatLabel}:`);
        const creatures: string[] = [];
        const others: string[] = [];
        for (const obj of objects) {
          const label = formatCard(obj, resolver);
          if (obj.cardTypes.some((t) => t.includes("Creature"))) {
            creatures.push(label);
          } else {
            others.push(label);
          }
        }
        if (creatures.length > 0) console.log(`  ${creatures.join(", ")}`);
        if (others.length > 0) console.log(`  ${others.join(", ")}`);
        continue;
      }

      if (zt === "Hand") {
        const names = objects.map((o) => cardName(o, resolver));
        console.log(`${zt}${seatLabel} (${objects.length}): ${names.join(", ")}`);
        continue;
      }

      if (zt === "Stack") {
        const names = objects.map((o) => cardName(o, resolver));
        console.log(`${zt}: ${names.join(", ")}`);
        continue;
      }

      // Graveyard, Exile, Command
      if (objects.length <= 5) {
        const names = objects.map((o) => cardName(o, resolver));
        console.log(`${zt}${seatLabel} (${objects.length}): ${names.join(", ")}`);
      } else {
        console.log(`${zt}${seatLabel}: ${objects.length} cards`);
      }
    }
  }

  // Actions available
  const actions = state.actions.filter((a: any) => {
    const action = a.action ?? a;
    const atype = action.actionType ?? "";
    return !atype.includes("Activate_Mana") && !atype.includes("FloatMana") && (a.seatId === game.ourSeat || !a.seatId);
  });
  if (actions.length > 0) {
    console.log("");
    console.log("Actions:");
    for (const a of actions) {
      const action = a.action ?? a;
      const atype = stripPrefix(action.actionType ?? "", "ActionType_");
      const grpId = action.grpId ?? 0;
      const iid = action.instanceId ?? 0;
      let name = grpId ? resolver?.resolve(grpId) : null;
      if (!name && iid) {
        const obj = acc.findObject(iid);
        if (obj) name = resolver?.resolve(obj.grpId) ?? null;
      }
      const cardLabel = name ?? (grpId ? `grp=${grpId}` : `iid=${iid}`);
      const cost = formatManaCost(action.manaCost);
      const costStr = cost ? ` (${cost})` : "";
      console.log(`  ${atype}: ${cardLabel}${costStr} (iid=${iid})`);
    }
  }
}

function cardName(obj: GameObject, resolver: CardResolver | null): string {
  if (obj.type === "GameObjectType_Ability" && resolver?.db) {
    // Resolve source card name, fall back to ability grpId
    const srcName = obj.objectSourceGrpId ? resolver.resolve(obj.objectSourceGrpId) : null;
    return srcName ? `${srcName} ability` : `grp=${obj.grpId}`;
  }
  const name = resolver?.resolve(obj.grpId);
  return name ?? `grp=${obj.grpId}`;
}

const MANA_LETTER: Record<string, string> = {
  ManaColor_White: "W", ManaColor_Blue: "U", ManaColor_Black: "B",
  ManaColor_Red: "R", ManaColor_Green: "G", ManaColor_Colorless: "C",
};

function formatManaCost(manaCost: any[] | undefined): string | null {
  if (!manaCost || manaCost.length === 0) return null;
  const parts: string[] = [];
  for (const c of manaCost) {
    const colors: string[] = c.color ?? [];
    const count: number = c.count ?? 0;
    if (colors.length === 0 || colors[0] === "ManaColor_Generic") {
      if (count > 0) parts.push(String(count));
    } else {
      const letter = MANA_LETTER[colors[0]] ?? "?";
      parts.push(count > 1 ? `${count}${letter}` : letter);
    }
  }
  return parts.join("") || null;
}

function formatCard(obj: GameObject, resolver: CardResolver | null): string {
  const name = cardName(obj, resolver);
  const parts = [name];
  if (obj.power != null && obj.toughness != null) {
    parts.push(`${obj.power}/${obj.toughness}`);
  }
  if (obj.loyalty != null) parts.push(`loyalty=${obj.loyalty}`);
  if (obj.isTapped) parts.push("(T)");
  if (obj.hasSummoningSickness) parts.push("(sick)");
  if (obj.damage > 0) parts.push(`${obj.damage}dmg`);
  return parts.join(" ");
}
