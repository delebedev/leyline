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

/** Format an object one-liner for gsm show. */
export function fmtObject(obj: any, resolver: CardResolver | null): string {
  const otype = stripPrefix(obj.type ?? "", "GameObjectType_");
  const parts = [`iid=${obj.instanceId}`, `grp=${fmtGrp(obj.grpId, resolver)}`];
  parts.push(zoneName(obj.zone?.type ?? "") || `zone=${obj.zoneId}`);
  if (obj.ownerSeatId) parts.push(`owner=${obj.ownerSeatId}`);
  if (obj.power) parts.push(`${obj.power.value}/${obj.toughness?.value ?? "?"}`);
  if (obj.isTapped) parts.push("tapped");
  return `${otype} ${parts.join("  ")}`;
}
