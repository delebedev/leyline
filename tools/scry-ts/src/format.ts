/**
 * Shared formatting helpers for human-readable output.
 *
 * Rule: enrich enums (zones, phases, types), keep numeric trace handles
 * (instanceId, grpId, annotationId).
 */

import { type CardResolver } from "./cards";

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

