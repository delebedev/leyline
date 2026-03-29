import { loadEvents } from "../log";
import { detectGames, type Game, type GsmSummary } from "../games";
import { getResolver } from "../cards";
import { stripPrefix, fmtGrp, zoneName } from "../format";

/** Parse --game/--all flags, return selected games. */
async function selectGames(args: string[]): Promise<{ allGames: Game[]; selected: Game[] }> {
  const events = await loadEvents();
  const allGames = detectGames(events);

  if (allGames.length === 0) {
    console.error("No games found in Player.log");
    process.exit(1);
  }

  const all = args.includes("--all");
  let gameIdx: number | null = null;
  for (let i = 0; i < args.length; i++) {
    if (args[i] === "--game" && i + 1 < args.length) {
      const val = args[++i];
      gameIdx = val === "last" ? allGames.length : parseInt(val, 10);
    }
  }

  let selected: Game[];
  if (all) {
    selected = allGames;
  } else {
    const idx = gameIdx ?? allGames.length;
    const game = allGames[idx - 1];
    if (!game) {
      console.error(`Game #${idx} not found (${allGames.length} games available)`);
      process.exit(1);
    }
    selected = [game];
  }

  return { allGames, selected };
}

export async function gsmCommand(args: string[]) {
  const verb = args[0];

  if (!verb || verb === "--help" || verb === "-h") {
    console.log("Usage: scry gsm <command>\n");
    console.log("Commands:");
    console.log("  list     List GSMs (default: last game)");
    console.log("  show N   Show full GSM detail by gsId");
    console.log("\nFlags:");
    console.log("  --game N     Select game by index");
    console.log("  --game last  Most recent game (default)");
    console.log("  --all        All games in log");
    console.log("  --has TYPE   Filter by annotation type (repeatable)");
    console.log("  --json       Output raw JSON (gsm show only)");
    return;
  }

  if (verb === "list") {
    await gsmList(args.slice(1));
  } else if (verb === "show") {
    await gsmShow(args.slice(1));
  } else {
    console.error(`Unknown gsm command: ${verb}\nRun 'scry gsm --help' for usage.`);
    process.exit(1);
  }
}

async function gsmList(args: string[]) {
  const { selected } = await selectGames(args);

  const hasFilters: string[] = [];
  for (let i = 0; i < args.length; i++) {
    if (args[i] === "--has" && i + 1 < args.length) {
      hasFilters.push(args[++i]);
    }
  }

  let gsms: { game: Game; gsm: GsmSummary }[] = [];
  for (const game of selected) {
    for (const gsm of game.greMessages) {
      gsms.push({ game, gsm });
    }
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

  const showGameCol = selected.length > 1;
  const header = [
    showGameCol ? "Game" : null,
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
      showGameCol ? `#${game.index}`.padStart(4) : null,
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

async function gsmShow(args: string[]) {
  const gsIdArg = args.find((a) => !a.startsWith("-"));
  if (!gsIdArg) {
    console.error("Usage: scry gsm show <gsId>");
    process.exit(1);
  }
  const targetGsId = parseInt(gsIdArg, 10);
  const jsonMode = args.includes("--json");

  const { selected } = await selectGames(args);

  let found: GsmSummary | null = null;
  for (const game of selected) {
    for (const gsm of game.greMessages) {
      if (gsm.gsId === targetGsId) {
        found = gsm;
        break;
      }
    }
    if (found) break;
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
      const parts = [`iid=${obj.instanceId}`, `grp=${fmtGrp(obj.grpId, resolver)}`];
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
