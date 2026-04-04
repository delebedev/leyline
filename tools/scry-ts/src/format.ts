/**
 * Shared formatting helpers for human-readable output.
 *
 * Rule: enrich enums (zones, phases, types), keep numeric trace handles
 * (instanceId, grpId, annotationId).
 */

import { type CardResolver } from "./cards";
import type { RawAnnotation, RawAction } from "./protocol";

/** Strip Arena protobuf enum prefixes. */
export function stripPrefix(val: string, ...prefixes: string[]): string {
  for (const p of prefixes) {
    if (val.startsWith(p)) return val.slice(p.length);
  }
  return val;
}

/** Zone type enum → readable name. */
export function zoneName(zoneType: string): string {
  return stripPrefix(zoneType, "ZoneType_");
}

/** Format a grpId with optional card name: "80165 (Llanowar Elves)" or "80165". */
export function fmtGrp(grpId: number, resolver: CardResolver | null): string {
  if (!grpId) return "0";
  const name = resolver?.resolve(grpId);
  return name ? `${grpId} (${name})` : String(grpId);
}

/** Phase + step → readable string: "Main1", "Combat/DeclareAttack". */
export function formatPhase(phase: string, step: string): string {
  const p = stripPrefix(phase, "Phase_");
  const s = step ? stripPrefix(step, "Step_") : "";
  return s ? `${p}/${s}` : p;
}

// --- Annotation rendering ---

/** Format a detail value, enriching zone IDs when a zone resolver is provided. */
function formatDetailValue(
  key: string,
  rawVals: (string | number)[],
  fmtZone?: (zoneId: number) => string,
): string {
  const isZoneKey = key === "zone_src" || key === "zone_dest";
  return rawVals.map((v) =>
    isZoneKey && typeof v === "number" && fmtZone ? fmtZone(v) : String(v)
  ).join(", ") || "?";
}

/**
 * Render an annotation list to lines (no trailing newline).
 * Pass fmtZone to enrich zone_src/zone_dest detail keys.
 */
export function formatAnnotations(
  anns: RawAnnotation[],
  opts?: { fmtZone?: (zoneId: number) => string },
): string[] {
  const lines: string[] = [];
  for (const ann of anns) {
    const types = (ann.type ?? []).map((t) => stripPrefix(t, "AnnotationType_"));
    const affector = ann.affectorId ?? "—";
    const affected = ann.affectedIds ?? [];
    lines.push(`  [${ann.id ?? "?"}] ${types.join(", ")}  affector=${affector}  affected=[${affected.join(", ")}]`);

    for (const d of ann.details ?? []) {
      const rawVals: (string | number)[] =
        d.valueString?.length ? d.valueString :
        d.valueInt32?.length ? d.valueInt32 :
        d.valueUint32?.length ? d.valueUint32 : [];
      lines.push(`       ${d.key} = ${formatDetailValue(d.key, rawVals, opts?.fmtZone)}`);
    }
  }
  return lines;
}

// --- Action rendering ---

const MANA_LETTER: Record<string, string> = {
  ManaColor_White: "W", ManaColor_Blue: "U", ManaColor_Black: "B",
  ManaColor_Red: "R", ManaColor_Green: "G", ManaColor_Colorless: "C",
};

/** Format a manaCost array to compact string: "2UU", "1B", null if empty. */
export function formatManaCost(manaCost: { color?: string[]; count?: number }[] | undefined): string | null {
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

