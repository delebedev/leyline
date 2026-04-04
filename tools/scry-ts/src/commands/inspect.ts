/**
 * `scry inspect <card>` — show accumulated state of a permanent.
 *
 * Resolves card by name (fuzzy) or instanceId, then shows:
 * - Object snapshot (zone, P/T, tapped, damage, types)
 * - Persistent annotations targeting this object
 * - Attachments (auras/equipment with parentId pointing here)
 * - Raw object fields not in the typed interface (viewers, counters, etc.)
 */

import { resolveGame } from "../resolve";
import { Accumulator, type GameObject, type GameState } from "../accumulator";
import { getResolver, resolveAbility, type CardResolver } from "../cards";
import { stripPrefix, fmtGrp, zoneName, formatAnnotations } from "../format";

export async function inspectCommand(args: string[]) {
  if (args[0] === "--help" || args[0] === "-h" || !args[0]) {
    console.log("Usage: scry inspect <card-name-or-iid> [flags]\n");
    console.log("Show accumulated state of a permanent.\n");
    console.log("Arguments:");
    console.log("  <card>       Card name (fuzzy substring) or instanceId (numeric)");
    console.log("\nFlags:");
    console.log("  --game REF   Game reference (catalog filename or live index)");
    console.log("  --gsid N     Inspect state at specific gsId");
    console.log("  --json       Output raw JSON");
    return;
  }

  // Collect the card query — everything before flags
  const flagPrefixes = ["--game", "--gsid", "--json"];
  const queryParts: string[] = [];
  for (let i = 0; i < args.length; i++) {
    if (flagPrefixes.includes(args[i])) {
      i++; // skip flag value
      continue;
    }
    if (args[i] === "--json") continue;
    queryParts.push(args[i]);
  }
  const query = queryParts.join(" ").trim();
  if (!query) {
    console.error("Usage: scry inspect <card-name-or-iid>");
    process.exit(1);
  }
  const queryAsNum = parseInt(query, 10);
  const isNumeric = !isNaN(queryAsNum) && String(queryAsNum) === query;

  // Parse --gsid
  let targetGsId: number | null = null;
  for (let i = 0; i < args.length; i++) {
    if (args[i] === "--gsid" && i + 1 < args.length) {
      targetGsId = parseInt(args[++i], 10);
    }
  }
  const jsonMode = args.includes("--json");

  const { game } = await resolveGame(args);
  const resolver = getResolver();

  // Replay to target gsId
  const acc = new Accumulator();
  for (const gsm of game.greMessages) {
    acc.apply(gsm.raw);
    if (targetGsId != null && gsm.gsId === targetGsId) break;
  }

  if (!acc.current) {
    console.error("No game state accumulated");
    process.exit(1);
  }

  const state = acc.current;

  // Build zone map (needed for search prioritization + display)
  const zoneMap = new Map<number, string>();
  for (const [, z] of state.zones) {
    const name = zoneName(z.type);
    const owner = z.ownerSeatId ? ` (seat ${z.ownerSeatId})` : "";
    zoneMap.set(z.zoneId, `${name}${owner}`);
  }
  const fmtZone = (zid: number) => zoneMap.get(zid) ?? `zone=${zid}`;

  // Find the target object
  let target: GameObject | null = null;

  if (isNumeric) {
    // By instanceId — follow id chain
    target = acc.findObject(queryAsNum);
  }

  if (!target && resolver) {
    // By name — fuzzy substring match, prefer battlefield/stack/hand
    const lowerQuery = query.toLowerCase();
    const zonePriority: Record<string, number> = {
      Battlefield: 0, Stack: 1, Hand: 2, Command: 3,
      Exile: 4, Graveyard: 5, Library: 6, Limbo: 7,
    };
    const candidates: GameObject[] = [];
    for (const [, obj] of state.objects) {
      const name = resolver.resolve(obj.grpId);
      if (name && name.toLowerCase().includes(lowerQuery)) {
        candidates.push(obj);
      }
    }
    if (candidates.length > 0) {
      candidates.sort((a, b) => {
        const za = zoneMap.get(a.zoneId) ?? "zzz";
        const zb = zoneMap.get(b.zoneId) ?? "zzz";
        const pa = zonePriority[za.split(" ")[0]] ?? 99;
        const pb = zonePriority[zb.split(" ")[0]] ?? 99;
        return pa - pb;
      });
      target = candidates[0];
    }
  }

  if (!target) {
    console.error(`Card not found: "${query}"`);
    process.exit(1);
  }

  // Collect persistent annotations targeting this object
  const iid = target.instanceId;
  const relatedAnns: any[] = [];
  for (const [, ann] of state.persistentAnnotations) {
    const affected = ann.affectedIds ?? [];
    if (affected.includes(iid) || ann.affectorId === iid) {
      relatedAnns.push(ann);
    }
  }

  // Collect attachments (objects with parentId === this iid)
  const attachments: GameObject[] = [];
  for (const [, obj] of state.objects) {
    if (obj.parentId === iid && obj.instanceId !== iid) {
      attachments.push(obj);
    }
  }

  // Find raw object from the last GSM that contained it (for extra fields)
  let rawObj: any = null;
  const gsms = [...game.greMessages];
  for (let i = gsms.length - 1; i >= 0; i--) {
    if (targetGsId != null && gsms[i].gsId > targetGsId) continue;
    const objects = gsms[i].raw.gameObjects ?? [];
    const found = objects.find((o: any) => o.instanceId === iid);
    if (found) {
      rawObj = found;
      break;
    }
  }

  if (jsonMode) {
    console.log(JSON.stringify({
      object: target,
      rawObject: rawObj,
      persistentAnnotations: relatedAnns,
      attachments: attachments.map(a => ({ instanceId: a.instanceId, grpId: a.grpId, type: a.type })),
    }, null, 2));
    return;
  }

  // Render
  const cardName = resolver?.resolve(target.grpId) ?? `grp=${target.grpId}`;
  const otype = stripPrefix(target.type, "GameObjectType_");
  console.log(`${cardName}  (iid=${iid}  grp=${target.grpId})`);
  console.log(`  Type: ${otype}  Zone: ${fmtZone(target.zoneId)}`);
  console.log(`  Owner: seat ${target.ownerSeatId}  Controller: seat ${target.controllerSeatId}`);

  if (target.cardTypes.length > 0) {
    const types = target.cardTypes.map(t => stripPrefix(t, "CardType_"));
    const subs = target.subtypes.map(t => stripPrefix(t, "SubType_"));
    const typeLine = subs.length > 0 ? `${types.join(" ")} — ${subs.join(" ")}` : types.join(" ");
    console.log(`  Types: ${typeLine}`);
  }
  if (target.colors.length > 0) {
    console.log(`  Colors: ${target.colors.map(c => stripPrefix(c, "CardColor_")).join(", ")}`);
  }
  if (target.power != null && target.toughness != null) {
    console.log(`  P/T: ${target.power}/${target.toughness}${target.damage > 0 ? `  (${target.damage} damage)` : ""}`);
  }
  if (target.loyalty != null) {
    console.log(`  Loyalty: ${target.loyalty}`);
  }

  const flags: string[] = [];
  if (target.isTapped) flags.push("tapped");
  if (target.hasSummoningSickness) flags.push("summoning sickness");
  if (target.isFacedown) flags.push("facedown");
  if (target.attackState && target.attackState !== "AttackState_None") flags.push(`attack=${stripPrefix(target.attackState, "AttackState_")}`);
  if (target.blockState && target.blockState !== "BlockState_None") flags.push(`block=${stripPrefix(target.blockState, "BlockState_")}`);
  if (flags.length > 0) console.log(`  State: ${flags.join(", ")}`);

  // Raw extras (viewers, uniqueAbilities, counters)
  if (rawObj) {
    if (rawObj.viewers?.length > 0) {
      console.log(`  Viewers: [${rawObj.viewers.join(", ")}] (revealed to these seats)`);
    }
    if (rawObj.uniqueAbilities?.length > 0) {
      const abilityLabels = rawObj.uniqueAbilities.map((a: any) => {
        const grpId = a.grpId ?? a.id ?? 0;
        if (resolver?.db) {
          const info = resolveAbility(resolver.db, grpId);
          if (info) return `${grpId} (${info.text.slice(0, 50)}${info.text.length > 50 ? "…" : ""})`;
        }
        return String(grpId);
      });
      console.log(`  Abilities: ${abilityLabels.join(", ")}`);
    }
  }

  // Attachments
  if (attachments.length > 0) {
    console.log("");
    console.log("Attachments:");
    for (const att of attachments) {
      const attName = resolver?.resolve(att.grpId) ?? `grp=${att.grpId}`;
      console.log(`  ${attName} (iid=${att.instanceId})`);
    }
  }

  // Persistent annotations
  if (relatedAnns.length > 0) {
    console.log("");
    console.log("Persistent Annotations:");
    for (const line of formatAnnotations(relatedAnns)) console.log(line);
  }
}
