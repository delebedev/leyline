/**
 * Shared formatting helpers for human-readable output.
 *
 * Rule: enrich enums (zones, phases, types), keep numeric trace handles
 * (instanceId, grpId, annotationId).
 */

import chalk from "chalk";
import { type CardResolver } from "./cards";
import type { RawAnnotation, RawAction } from "./protocol";

// --- Colors ---

export const c = {
  card: chalk.bold,
  zone: chalk.cyan,
  life: (v: number) => v > 0 ? chalk.green(`+${v}`) : chalk.red(String(v)),
  lifeTotal: chalk.bold,
  manaW: chalk.white.bold,
  manaU: chalk.blue.bold,
  manaB: chalk.dim,
  manaR: chalk.red.bold,
  manaG: chalk.green.bold,
  manaC: chalk.gray,
  manaGeneric: chalk.yellow,
  annType: chalk.yellow,
  prompt: chalk.magenta.bold,
  gameOver: (result: string) => result === "win" ? chalk.green.bold : chalk.red.bold,
  dim: chalk.dim,
  key: chalk.dim,
};

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
  return name ? `${grpId} (${c.card(name)})` : String(grpId);
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
    const types = (ann.type ?? []).map((t) => c.annType(stripPrefix(t, "AnnotationType_")));
    const affector = ann.affectorId ?? "—";
    const affected = ann.affectedIds ?? [];
    lines.push(`  [${ann.id ?? "?"}] ${types.join(", ")}  ${c.dim("affector=")}${affector}  ${c.dim("affected=")}[${affected.join(", ")}]`);

    for (const d of ann.details ?? []) {
      const rawVals: (string | number)[] =
        d.valueString?.length ? d.valueString :
        d.valueInt32?.length ? d.valueInt32 :
        d.valueUint32?.length ? d.valueUint32 : [];
      lines.push(`       ${c.key(d.key)} = ${formatDetailValue(d.key, rawVals, opts?.fmtZone)}`);
    }
  }
  return lines;
}

// --- Action rendering ---

const MANA_LETTER: Record<string, string> = {
  ManaColor_White: "W", ManaColor_Blue: "U", ManaColor_Black: "B",
  ManaColor_Red: "R", ManaColor_Green: "G", ManaColor_Colorless: "C",
};

const MANA_COLOR_FN: Record<string, (s: string) => string> = {
  ManaColor_White: (s) => c.manaW(s),
  ManaColor_Blue: (s) => c.manaU(s),
  ManaColor_Black: (s) => c.manaB(s),
  ManaColor_Red: (s) => c.manaR(s),
  ManaColor_Green: (s) => c.manaG(s),
  ManaColor_Colorless: (s) => c.manaC(s),
};

/** Format a manaCost array to compact string: "2UU", "1B", null if empty. */
export function formatManaCost(manaCost: { color?: string[]; count?: number }[] | undefined): string | null {
  if (!manaCost || manaCost.length === 0) return null;
  const parts: string[] = [];
  for (const mc of manaCost) {
    const colors: string[] = mc.color ?? [];
    const count: number = mc.count ?? 0;
    if (colors.length === 0 || colors[0] === "ManaColor_Generic") {
      if (count > 0) parts.push(c.manaGeneric(String(count)));
    } else {
      const letter = MANA_LETTER[colors[0]] ?? "?";
      const colorFn = MANA_COLOR_FN[colors[0]] ?? ((s: string) => s);
      parts.push(colorFn(count > 1 ? `${count}${letter}` : letter));
    }
  }
  return parts.join("") || null;
}

