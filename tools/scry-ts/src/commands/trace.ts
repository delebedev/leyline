import { resolveGame } from "../resolve";
import { Accumulator } from "../accumulator";
import { getResolver } from "../cards";
import { stripPrefix, fmtGrp, zoneName, formatPhase } from "../format";

export async function traceCommand(args: string[]) {
  if (!args[0] || args[0] === "--help" || args[0] === "-h") {
    console.log("Usage: scry trace <card-name-or-instanceId> [flags]\n");
    console.log("Trace a card's journey through a game — every zone change,\n" +
                "annotation, and instance ID change.\n");
    console.log("Flags:");
    console.log("  --game REF   Game reference (catalog filename substring, or live index)");
    console.log("  --gsid N     Show only annotations from a specific GSM");
    console.log("  --json       Output raw annotation JSON (pipe to jq)");
    return;
  }

  const target = args[0];
  const isNumeric = /^\d+$/.test(target);
  const targetInstanceId = isNumeric ? parseInt(target, 10) : null;
  const jsonMode = args.includes("--json");

  let filterGsId: number | null = null;
  for (let i = 0; i < args.length; i++) {
    if (args[i] === "--gsid" && i + 1 < args.length) {
      filterGsId = parseInt(args[++i], 10);
    }
  }

  const resolver = getResolver();
  const { game, source, label } = await resolveGame(args);

  // Replay through accumulator, collecting trace events
  const acc = new Accumulator();
  const trace: TraceEvent[] = [];

  // If target is a name, we need to find the grpId first
  let targetGrpIds: Set<number> | null = null;
  if (!isNumeric && resolver) {
    // Scan all GSMs for objects, find grpIds matching the name
    targetGrpIds = new Set<number>();
    for (const gsm of game.greMessages) {
      for (const obj of gsm.raw.gameObjects ?? []) {
        const name = resolver.resolve(obj.grpId);
        if (name && name.toLowerCase().includes(target.toLowerCase())) {
          targetGrpIds.add(obj.grpId);
        }
      }
    }
    if (targetGrpIds.size === 0) {
      console.error(`No card matching "${target}" found in game`);
      process.exit(1);
    }
  }

  // Track which instanceIds belong to our target card
  const trackedIds = new Set<number>();

  // Build zone map per GSM for readable output
  for (const gsm of game.greMessages) {
    acc.apply(gsm.raw);
    if (!acc.current) continue;

    const gs = acc.current;
    const zoneMap = new Map<number, string>();
    for (const [, z] of gs.zones) {
      const name = zoneName(z.type);
      const owner = z.ownerSeatId ? ` (seat ${z.ownerSeatId})` : "";
      zoneMap.set(z.zoneId, `${name}${owner}`);
    }
    const fmtZone = (id: number) => zoneMap.get(id) ?? `zone=${id}`;

    // Discover instanceIds for our target
    for (const [, obj] of gs.objects) {
      let match = false;
      if (targetInstanceId != null) {
        // Check if this object is reachable from the target via id chain
        const resolved = acc.resolveId(targetInstanceId);
        if (obj.instanceId === resolved || obj.instanceId === targetInstanceId) match = true;
        // Also check if target is in this object's history
        const chain = acc.traceBack(obj.instanceId);
        if (chain.includes(targetInstanceId)) match = true;
      } else if (targetGrpIds?.has(obj.grpId)) {
        match = true;
      }
      if (match) trackedIds.add(obj.instanceId);
    }

    // Check annotations for references to tracked IDs
    for (const ann of gs.annotations) {
      const types = (ann.type ?? []).map((t: string) => stripPrefix(t, "AnnotationType_"));
      const affector = ann.affectorId ?? 0;
      const affected: number[] = ann.affectedIds ?? [];
      const allRefs = [affector, ...affected].filter((id) => id > 2);

      // Does this annotation reference any tracked ID?
      const relevant = allRefs.some((id) => {
        if (trackedIds.has(id)) return true;
        // Check through id chain
        const resolved = acc.resolveId(id);
        if (trackedIds.has(resolved)) return true;
        const chain = acc.traceBack(id);
        return chain.some((c) => trackedIds.has(c));
      });

      if (!relevant) continue;

      // Track new IDs from ObjectIdChanged
      if (types.includes("ObjectIdChanged")) {
        for (const d of ann.details ?? []) {
          if (d.key === "new_id") {
            const newId = d.valueInt32?.[0] ?? d.valueUint32?.[0];
            if (newId) trackedIds.add(newId);
          }
        }
      }

      // Build detail string
      const details: string[] = [];
      for (const d of ann.details ?? []) {
        const rawVals: (string | number)[] =
          d.valueString?.length ? d.valueString :
          d.valueInt32?.length ? d.valueInt32 :
          d.valueUint32?.length ? d.valueUint32 : [];
        const key = d.key as string;
        const isZoneKey = key === "zone_src" || key === "zone_dest";
        const vals = rawVals.map((v: string | number) =>
          isZoneKey && typeof v === "number" ? fmtZone(v) : String(v)
        ).join(", ");
        details.push(`${key}=${vals}`);
      }

      trace.push({
        gsId: gs.gameStateId,
        turn: gs.turnInfo?.turnNumber ?? 0,
        phase: formatPhase(gs.turnInfo?.phase ?? "", gs.turnInfo?.step ?? ""),
        types,
        affector,
        affected,
        details,
        raw: ann,
      });
    }
  }

  // Print header
  const cardLabel = isNumeric
    ? `instanceId ${target}`
    : `"${target}"`;
  const grpLabel = targetGrpIds?.size
    ? ` (grp=${[...targetGrpIds].join(", ")})`
    : "";
  const idsLabel = trackedIds.size > 0
    ? ` — instanceIds: [${[...trackedIds].sort((a, b) => a - b).join(", ")}]`
    : "";
  if (!jsonMode) {
    console.log(`Trace: ${cardLabel}${grpLabel}${idsLabel}`);
    console.log(`Game: ${label}`);
    console.log("");
  }

  // Apply --gsid filter
  let filtered = filterGsId != null ? trace.filter((e) => e.gsId === filterGsId) : trace;

  if (filtered.length === 0) {
    console.log(filterGsId != null
      ? `No annotations for this card at gsId ${filterGsId}.`
      : "No annotations found for this card.");
    return;
  }

  // JSON mode — raw annotations, clean for piping
  if (jsonMode) {
    console.error(`Trace: ${cardLabel}${grpLabel}${idsLabel}`);
    console.error(`Game: ${label}\n`);
    console.log(JSON.stringify(filtered.map((e) => ({
      gsId: e.gsId,
      turn: e.turn,
      phase: e.phase,
      annotation: e.raw,
    })), null, 2));
    return;
  }

  // Print trace
  let lastTurn = -1;
  for (const ev of filtered) {
    if (ev.turn !== lastTurn) {
      if (lastTurn !== -1) console.log("");
      console.log(`T${ev.turn} ${ev.phase}`);
      lastTurn = ev.turn;
    }
    const detailStr = ev.details.length > 0 ? `  ${ev.details.join("  ")}` : "";
    const affectedStr = ev.affected.length > 0 ? ` → [${ev.affected.join(", ")}]` : "";
    console.log(`  gs=${ev.gsId}  ${ev.types.join(", ")}  from=${ev.affector}${affectedStr}${detailStr}`);
  }

  console.log(`\n${filtered.length} annotations across ${new Set(filtered.map((e) => e.gsId)).size} GSMs`);
}

interface TraceEvent {
  gsId: number;
  turn: number;
  phase: string;
  types: string[];
  affector: number;
  affected: number[];
  details: string[];
  raw: any;
}

