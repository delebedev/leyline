import { type Game, type GsmSummary } from "../games";
import { resolveGame } from "../resolve";
import { Accumulator, type GameState, type GameObject } from "../accumulator";
import { getResolver, resolveAbility, type CardResolver } from "../cards";
import { stripPrefix, fmtGrp, zoneName, formatPhase } from "../format";

export async function gsmCommand(args: string[]) {
  const verb = args[0];

  if (!verb || verb === "--help" || verb === "-h") {
    console.log("Usage: scry gsm <command>\n");
    console.log("Commands:");
    console.log("  list     List GSMs (default: last game)");
    console.log("  show N   Show full GSM detail by gsId");
    console.log("  diff A B Diff accumulated state between two gsIds");
    console.log("\nFlags:");
    console.log("  --game N     Select game by index");
    console.log("  --game last  Most recent game (default)");
    console.log("  --all        All games in log");
    console.log("  --has TYPE   Filter by annotation type (repeatable)");
    console.log("  --view V     Projection: default, turns, actions");
    console.log("  --json       Output raw JSON (gsm show only)");
    return;
  }

  if (verb === "list") {
    await gsmList(args.slice(1));
  } else if (verb === "show") {
    await gsmShow(args.slice(1));
  } else if (verb === "diff") {
    await gsmDiff(args.slice(1));
  } else {
    console.error(`Unknown gsm command: ${verb}\nRun 'scry gsm --help' for usage.`);
    process.exit(1);
  }
}

async function gsmList(args: string[]) {
  const { game } = await resolveGame(args);

  const hasFilters: string[] = [];
  for (let i = 0; i < args.length; i++) {
    if (args[i] === "--has" && i + 1 < args.length) {
      hasFilters.push(args[++i]);
    }
  }

  let gsms: { game: Game; gsm: GsmSummary }[] = [];
  for (const gsm of game.greMessages) {
    gsms.push({ game, gsm });
  }

  if (hasFilters.length > 0) {
    gsms = gsms.filter(({ gsm }) =>
      hasFilters.every((f) => gsm.annotationTypes.some((t) => t.includes(f)))
    );
  }

  if (gsms.length === 0) {
    console.log("No matching GSMs.");
    return;
  }

  // Parse --view flag
  let view = "default";
  for (let i = 0; i < args.length; i++) {
    if (args[i] === "--view" && i + 1 < args.length) {
      view = args[++i];
    }
  }

  if (view === "turns") {
    renderTurns(gsms);
  } else if (view === "annotations") {
    renderAnnotations(gsms, hasFilters);
  } else if (view === "actions") {
    renderActions(gsms);
  } else {
    renderDefault(gsms);
  }
}

function renderDefault(gsms: { game: Game; gsm: GsmSummary }[]) {
  const header = [
    "gsId".padStart(4),
    "Type".padEnd(4),
    "Turn".padStart(4),
    "Phase".padEnd(20),
    "Ann".padStart(3),
    "Obj".padStart(3),
    "Annotations",
  ]
    .filter(Boolean)
    .join("  ");
  console.log(header);
  console.log("—".repeat(header.length));

  for (const { game, gsm } of gsms) {
    const phase = gsm.step ? `${gsm.phase}/${gsm.step}` : gsm.phase;
    const types = gsm.annotationTypes.length > 0
      ? gsm.annotationTypes.join(", ")
      : "—";
    const cols = [
      String(gsm.gsId).padStart(4),
      gsm.type.padEnd(4),
      String(gsm.turn).padStart(4),
      phase.padEnd(20),
      String(gsm.annotationCount).padStart(3),
      String(gsm.objectCount).padStart(3),
      types,
    ]
      .filter(Boolean)
      .join("  ");
    console.log(cols);
  }

  console.log(`\n${gsms.length} GSMs`);
}

function renderTurns(gsms: { game: Game; gsm: GsmSummary }[]) {
  // Filter to GSMs that have turnInfo (skip bare echoes)
  const withTurn = gsms.filter(({ gsm }) => gsm.phase || gsm.step);

  console.log(`${"gsId".padStart(4)}  ${"Turn".padStart(4)}  ${"Phase".padEnd(20)}  ${"Active".padStart(6)}  ${"Priority".padStart(8)}`);
  console.log("—".repeat(50));

  for (const { gsm } of withTurn) {
    const phase = gsm.step ? `${gsm.phase}/${gsm.step}` : gsm.phase;
    console.log(
      `${String(gsm.gsId).padStart(4)}  ${String(gsm.turn).padStart(4)}  ${phase.padEnd(20)}  ${`seat${gsm.activePlayer}`.padStart(6)}  ${`seat${gsm.raw.turnInfo?.priorityPlayer ?? "?"}`.padStart(8)}`
    );
  }

  console.log(`\n${withTurn.length} turns`);
}

function renderAnnotations(gsms: { game: Game; gsm: GsmSummary }[], hasFilters: string[]) {
  // Build a zone map from the first Full GSM
  const zoneMap = new Map<number, string>();
  for (const { gsm } of gsms) {
    for (const z of gsm.raw.zones ?? []) {
      const name = zoneName(z.type ?? "");
      const owner = z.ownerSeatId ? ` (seat ${z.ownerSeatId})` : "";
      zoneMap.set(z.zoneId, `${name}${owner}`);
    }
  }
  const fmtZone = (id: number) => zoneMap.get(id) ?? `zone=${id}`;

  let count = 0;
  let lastTurn = -1;

  for (const { gsm } of gsms) {
    const transient = gsm.raw.annotations ?? [];
    const persistent = gsm.raw.persistentAnnotations ?? [];
    const allAnns = [...transient, ...persistent];
    if (allAnns.length === 0) continue;

    const phase = gsm.step ? `${gsm.phase}/${gsm.step}` : gsm.phase;
    if (gsm.turn !== lastTurn) {
      if (lastTurn !== -1) console.log("");
      lastTurn = gsm.turn;
    }

    for (const ann of allAnns) {
      // Filter to --has types when rendering (not just GSM-level filtering)
      if (hasFilters.length > 0) {
        const annTypes = (ann.type ?? []).map((t: string) => stripPrefix(t, "AnnotationType_"));
        if (!hasFilters.some((f) => annTypes.some((t: string) => t.includes(f)))) continue;
      }
      const types = (ann.type ?? []).map((t: string) => stripPrefix(t, "AnnotationType_"));
      const affector = ann.affectorId ?? "—";
      const affected: number[] = ann.affectedIds ?? [];
      const affectedStr = affected.length > 0 ? ` → [${affected.join(", ")}]` : "";

      // Build detail string with enrichment
      const detailParts: string[] = [];
      for (const d of ann.details ?? []) {
        const key = d.key as string;
        const rawVals: (string | number)[] =
          d.valueString?.length ? d.valueString :
          d.valueInt32?.length ? d.valueInt32 :
          d.valueUint32?.length ? d.valueUint32 : [];
        const isZoneKey = key === "zone_src" || key === "zone_dest";
        const vals = rawVals.map((v: string | number) =>
          isZoneKey && typeof v === "number" ? fmtZone(v) : String(v)
        ).join(", ");
        detailParts.push(`${key}=${vals}`);
      }
      const details = detailParts.length > 0 ? `  ${detailParts.join("  ")}` : "";

      console.log(`  gs=${gsm.gsId} T${gsm.turn} ${phase}  ${types.join(", ")}  from=${affector}${affectedStr}${details}`);
      count++;
    }
  }

  console.log(`\n${count} annotations`);
}

function renderActions(gsms: { game: Game; gsm: GsmSummary }[]) {
  const resolver = getResolver();

  // Extract actions from ZoneTransfer annotations + UserActionTaken
  const actions: { turn: number; gsId: number; seat: string; category: string; card: string }[] = [];

  for (const { gsm } of gsms) {
    const raw = gsm.raw;
    const annotations = raw.annotations ?? [];

    // Build instance→grpId map from objects in this GSM
    const grpById = new Map<number, number>();
    for (const obj of raw.gameObjects ?? []) {
      if (obj.instanceId && obj.grpId) grpById.set(obj.instanceId, obj.grpId);
    }

    for (const ann of annotations) {
      const types: string[] = ann.type ?? [];

      // ZoneTransfer — the primary action signal
      if (types.some((t: string) => t.includes("ZoneTransfer"))) {
        const details = ann.details ?? [];
        let category = "?";
        for (const d of details) {
          if (d.key === "category") {
            category = d.valueString?.[0] ?? "?";
          }
        }
        // Skip non-action categories
        if (category === "SBA_Damage" || category === "SBA_UnattachedAura" || category === "SBA_ZeroToughness") continue;

        const affectedId = ann.affectedIds?.[0] ?? 0;
        const affectorId = ann.affectorId ?? 0;
        // Card is the affected object (the thing that moved)
        const grpId = grpById.get(affectedId) ?? grpById.get(affectorId) ?? 0;
        const card = cardLabel(grpId, resolver);
        const seat = affectorId <= 2 && affectorId > 0 ? `seat${affectorId}` : "—";

        actions.push({
          turn: gsm.turn,
          gsId: gsm.gsId,
          seat,
          category,
          card,
        });
      }
    }
  }

  if (actions.length === 0) {
    console.log("No actions found.");
    return;
  }

  console.log(`${"Turn".padStart(4)}  ${"Seat".padEnd(5)}  ${"Action".padEnd(20)}  ${"Card".padEnd(30)}  gsId`);
  console.log("—".repeat(72));

  for (const a of actions) {
    console.log(
      `${String(a.turn).padStart(4)}  ${a.seat.padEnd(5)}  ${a.category.padEnd(20)}  ${a.card.padEnd(30)}  ${a.gsId}`
    );
  }

  console.log(`\n${actions.length} actions`);
}

function cardLabel(grpId: number, resolver: CardResolver | null): string {
  if (!grpId) return "?";
  const name = resolver?.resolve(grpId);
  return name ?? `grp=${grpId}`;
}

async function gsmDiff(args: string[]) {
  const nums = args.filter((a) => !a.startsWith("-")).map((a) => parseInt(a, 10)).filter((n) => !isNaN(n));
  if (nums.length < 2) {
    console.error("Usage: scry gsm diff <gsIdA> <gsIdB>");
    process.exit(1);
  }
  const [gsIdA, gsIdB] = nums;
  const jsonMode = args.includes("--json");

  const { game, label } = await resolveGame(args);
  const resolver = getResolver();

  // Replay to gsId A
  const accA = new Accumulator();
  for (const gsm of game.greMessages) {
    accA.apply(gsm.raw);
    if (gsm.gsId === gsIdA) break;
  }

  // Replay to gsId B
  const accB = new Accumulator();
  for (const gsm of game.greMessages) {
    accB.apply(gsm.raw);
    if (gsm.gsId === gsIdB) break;
  }

  if (!accA.current || !accB.current) {
    console.error(`Could not accumulate to both gsIds (${gsIdA}, ${gsIdB})`);
    process.exit(1);
  }

  const stateA = accA.current;
  const stateB = accB.current;

  if (jsonMode) {
    console.log(JSON.stringify({ from: gsIdA, to: gsIdB, a: serializeState(stateA), b: serializeState(stateB) }, null, 2));
    return;
  }

  const fmtCard = (obj: GameObject) => {
    const name = resolver?.resolve(obj.grpId);
    return name ?? `grp=${obj.grpId}`;
  };

  console.log(`Diff: gs=${gsIdA} → gs=${gsIdB} (${label})\n`);

  // Turn info
  const tiA = stateA.turnInfo;
  const tiB = stateB.turnInfo;
  if (tiA && tiB) {
    const phaseA = formatPhase(tiA.phase, tiA.step);
    const phaseB = formatPhase(tiB.phase, tiB.step);
    if (tiA.turnNumber !== tiB.turnNumber || phaseA !== phaseB) {
      console.log(`Turn: T${tiA.turnNumber} ${phaseA} → T${tiB.turnNumber} ${phaseB}`);
    }
  }

  // Life changes
  for (const seat of [1, 2]) {
    const lifeA = stateA.players.get(seat)?.lifeTotal ?? 0;
    const lifeB = stateB.players.get(seat)?.lifeTotal ?? 0;
    if (lifeA !== lifeB) {
      console.log(`Life seat ${seat}: ${lifeA} → ${lifeB} (${lifeB - lifeA >= 0 ? "+" : ""}${lifeB - lifeA})`);
    }
  }

  // Build zone maps for readable names
  const zoneMapB = new Map<number, string>();
  for (const [, z] of stateB.zones) {
    const name = zoneName(z.type);
    const owner = z.ownerSeatId ? ` (seat ${z.ownerSeatId})` : "";
    zoneMapB.set(z.zoneId, `${name}${owner}`);
  }
  const fmtZone = (zid: number) => zoneMapB.get(zid) ?? `zone=${zid}`;

  // Object changes
  const allIds = new Set([...stateA.objects.keys(), ...stateB.objects.keys()]);
  const added: string[] = [];
  const removed: string[] = [];
  const changed: string[] = [];

  for (const id of allIds) {
    const objA = stateA.objects.get(id);
    const objB = stateB.objects.get(id);

    if (!objA && objB) {
      added.push(`${fmtCard(objB)} (iid=${id}) → ${fmtZone(objB.zoneId)}`);
    } else if (objA && !objB) {
      removed.push(`${fmtCard(objA)} (iid=${id}) from ${fmtZone(objA.zoneId)}`);
    } else if (objA && objB) {
      const diffs: string[] = [];
      if (objA.zoneId !== objB.zoneId) diffs.push(`zone: ${fmtZone(objA.zoneId)} → ${fmtZone(objB.zoneId)}`);
      if (objA.power !== objB.power || objA.toughness !== objB.toughness) diffs.push(`P/T: ${objA.power}/${objA.toughness} → ${objB.power}/${objB.toughness}`);
      if (objA.isTapped !== objB.isTapped) diffs.push(objB.isTapped ? "tapped" : "untapped");
      if (objA.damage !== objB.damage) diffs.push(`damage: ${objA.damage} → ${objB.damage}`);
      if (objA.controllerSeatId !== objB.controllerSeatId) diffs.push(`controller: seat${objA.controllerSeatId} → seat${objB.controllerSeatId}`);
      if (diffs.length > 0) {
        changed.push(`${fmtCard(objB)} (iid=${id}): ${diffs.join(", ")}`);
      }
    }
  }

  if (added.length > 0) {
    console.log("\nAdded:");
    for (const line of added) console.log(`  + ${line}`);
  }
  if (removed.length > 0) {
    console.log("\nRemoved:");
    for (const line of removed) console.log(`  - ${line}`);
  }
  if (changed.length > 0) {
    console.log("\nChanged:");
    for (const line of changed) console.log(`  ~ ${line}`);
  }

  // Action changes
  const actionsA = extractActionSummaries(stateA, resolver);
  const actionsB = extractActionSummaries(stateB, resolver);
  const actionsAdded = actionsB.filter((b) => !actionsA.some((a) => a.key === b.key));
  const actionsRemoved = actionsA.filter((a) => !actionsB.some((b) => b.key === a.key));
  const costChanged: string[] = [];

  // Compare costs for actions present in both
  for (const b of actionsB) {
    const a = actionsA.find((x) => x.key === b.key);
    if (a && a.cost !== b.cost) {
      costChanged.push(`${b.label}: ${a.cost} → ${b.cost}`);
    }
  }

  if (actionsAdded.length > 0 || actionsRemoved.length > 0 || costChanged.length > 0) {
    console.log("\nActions:");
    for (const a of actionsAdded) console.log(`  + ${a.label} (${a.cost})`);
    for (const a of actionsRemoved) console.log(`  - ${a.label} (${a.cost})`);
    for (const c of costChanged) console.log(`  Δ ${c}`);
  }

  if (added.length === 0 && removed.length === 0 && changed.length === 0 && actionsAdded.length === 0 && actionsRemoved.length === 0 && costChanged.length === 0) {
    console.log("\nNo differences.");
  }
}

interface ActionSummary {
  key: string;   // dedup key: type+instanceId
  label: string; // human readable
  cost: string;  // mana cost string
}

function extractActionSummaries(state: GameState, resolver: CardResolver | null): ActionSummary[] {
  const result: ActionSummary[] = [];
  for (const a of state.actions) {
    const action = a.action ?? a;
    const atype = stripPrefix(action.actionType ?? "", "ActionType_");
    if (atype === "Activate_Mana" || atype === "Pass" || atype === "FloatMana") continue;
    const iid = action.instanceId ?? 0;
    const grpId = action.grpId ?? 0;
    let name = grpId ? resolver?.resolve(grpId) : null;
    if (!name && iid) {
      const obj = state.objects.get(iid);
      if (obj) name = resolver?.resolve(obj.grpId) ?? null;
    }
    name = name ?? (grpId ? `grp=${grpId}` : `iid=${iid}`);
    const manaCost = action.manaCost ?? [];
    const costStr = manaCost.length > 0
      ? manaCost.map((c: any) => {
          const colors = c.color ?? [];
          const count = c.count ?? 0;
          if (colors.length === 0) return String(count);
          const ch = stripPrefix(colors[0] ?? "", "ManaColor_")[0];
          return count > 1 ? `${count}${ch}` : ch;
        }).join("")
      : "0";

    result.push({
      key: `${atype}:${iid}`,
      label: `${atype}: ${name}`,
      cost: costStr,
    });
  }
  return result;
}

function serializeState(state: GameState): any {
  return {
    gameStateId: state.gameStateId,
    turnInfo: state.turnInfo,
    players: Object.fromEntries(state.players),
    objects: Object.fromEntries(state.objects),
    actions: state.actions,
  };
}

async function gsmShow(args: string[]) {
  const gsIdArg = args.find((a) => !a.startsWith("-"));
  if (!gsIdArg) {
    console.error("Usage: scry gsm show <gsId>");
    process.exit(1);
  }
  const targetGsId = parseInt(gsIdArg, 10);
  const jsonMode = args.includes("--json");

  const { game } = await resolveGame(args);

  let found: GsmSummary | null = null;
  for (const gsm of game.greMessages) {
    if (gsm.gsId === targetGsId) {
      found = gsm;
      break;
    }
  }

  if (!found) {
    console.error(`GSM ${targetGsId} not found`);
    process.exit(1);
  }

  if (jsonMode) {
    console.log(JSON.stringify(found.raw, null, 2));
    return;
  }

  const raw = found.raw;
  const resolver = getResolver();

  // Build zone lookup: zoneId → readable name
  const zoneMap = new Map<number, string>();
  for (const z of raw.zones ?? []) {
    const name = zoneName(z.type ?? "");
    const owner = z.ownerSeatId ? ` (seat ${z.ownerSeatId})` : "";
    zoneMap.set(z.zoneId, `${name}${owner}`);
  }
  const fmtZone = (zoneId: number) => zoneMap.get(zoneId) ?? `zone=${zoneId}`;

  // Header
  const phase = found.step ? `${found.phase}/${found.step}` : found.phase;
  console.log(`gsId=${found.gsId}  ${found.type}  T${found.turn} ${phase}  active=seat${found.activePlayer}`);
  console.log("");

  // Players
  const players = raw.players ?? [];
  if (players.length > 0) {
    console.log("Players:");
    for (const p of players) {
      console.log(`  seat ${p.systemSeatNumber ?? "?"}  life=${p.lifeTotal ?? "?"}`);
    }
    console.log("");
  }

  // Zones (only if they have objects)
  const zones = raw.zones ?? [];
  const zonesWithObjects = zones.filter((z: any) => (z.objectInstanceIds?.length ?? 0) > 0);
  if (zonesWithObjects.length > 0) {
    console.log("Zones:");
    for (const z of zonesWithObjects) {
      const ids = z.objectInstanceIds ?? [];
      console.log(`  ${fmtZone(z.zoneId)}: ${ids.length} objects [${ids.join(", ")}]`);
    }
    console.log("");
  }

  // Objects
  const objects = raw.gameObjects ?? [];
  if (objects.length > 0) {
    console.log("Objects:");
    for (const obj of objects) {
      const otype = stripPrefix(obj.type ?? "", "GameObjectType_");
      const parts = [`iid=${obj.instanceId}`];

      // Ability objects: resolve source card + ability text
      if (otype === "Ability" && resolver?.db) {
        const srcName = obj.objectSourceGrpId ? resolver.resolve(obj.objectSourceGrpId) : null;
        const abilityInfo = resolveAbility(resolver.db, obj.grpId);
        const label = srcName
          ? `${srcName} → ${abilityInfo?.text?.slice(0, 60) ?? `ability=${obj.grpId}`}`
          : `grp=${obj.grpId}`;
        parts.push(label);
      } else {
        parts.push(`grp=${fmtGrp(obj.grpId, resolver)}`);
      }

      parts.push(fmtZone(obj.zoneId));
      if (obj.ownerSeatId) parts.push(`owner=${obj.ownerSeatId}`);
      if (obj.power) parts.push(`${obj.power.value}/${obj.toughness?.value ?? "?"}`);
      if (obj.isTapped) parts.push("tapped");
      console.log(`  ${otype} ${parts.join("  ")}`);
    }
    console.log("");
  }

  // Annotations
  const annotations = raw.annotations ?? [];
  if (annotations.length > 0) {
    console.log("Annotations:");
    for (const ann of annotations) {
      const types = (ann.type ?? []).map((t: string) => stripPrefix(t, "AnnotationType_"));
      const affector = ann.affectorId ?? "—";
      const affected = ann.affectedIds ?? [];
      console.log(`  [${ann.id ?? "?"}] ${types.join(", ")}  affector=${affector}  affected=[${affected.join(", ")}]`);

      const details = ann.details ?? [];
      for (const d of details) {
        const rawVals: (string | number)[] =
          d.valueString?.length ? d.valueString :
          d.valueInt32?.length ? d.valueInt32 :
          d.valueUint32?.length ? d.valueUint32 :
          [];
        // Enrich known detail keys
        const key = d.key as string;
        const isZoneKey = key === "zone_src" || key === "zone_dest";
        const vals = rawVals.map((v: string | number) =>
          isZoneKey && typeof v === "number" ? fmtZone(v) : String(v)
        ).join(", ") || "?";
        console.log(`       ${key} = ${vals}`);
      }
    }
    console.log("");
  }

  // Persistent Annotations
  const persistentAnns = raw.persistentAnnotations ?? [];
  if (persistentAnns.length > 0) {
    console.log("Persistent Annotations:");
    for (const ann of persistentAnns) {
      const types = (ann.type ?? []).map((t: string) => stripPrefix(t, "AnnotationType_"));
      const affector = ann.affectorId ?? "—";
      const affected = ann.affectedIds ?? [];
      console.log(`  [${ann.id ?? "?"}] ${types.join(", ")}  affector=${affector}  affected=[${affected.join(", ")}]`);

      const details = ann.details ?? [];
      for (const d of details) {
        const rawVals: (string | number)[] =
          d.valueString?.length ? d.valueString :
          d.valueInt32?.length ? d.valueInt32 :
          d.valueUint32?.length ? d.valueUint32 :
          [];
        const key = d.key as string;
        const isZoneKey = key === "zone_src" || key === "zone_dest";
        const vals = rawVals.map((v: string | number) =>
          isZoneKey && typeof v === "number" ? fmtZone(v) : String(v)
        ).join(", ") || "?";
        console.log(`       ${key} = ${vals}`);
      }
    }
    console.log("");
  }

  // Actions
  const actions = raw.actions ?? [];
  if (actions.length > 0) {
    console.log("Actions:");
    for (const a of actions) {
      const action = a.action ?? a;
      const atype = stripPrefix(action.actionType ?? "", "ActionType_");
      const parts = [atype];
      if (action.instanceId) parts.push(`iid=${action.instanceId}`);
      if (action.grpId) parts.push(`grp=${fmtGrp(action.grpId, resolver)}`);
      if (a.seatId) parts.push(`seat=${a.seatId}`);
      console.log(`  ${parts.join("  ")}`);
    }
  }
}
