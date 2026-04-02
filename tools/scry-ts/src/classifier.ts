/**
 * GSM role classification — assigns a semantic role to each game state message
 * based on its transient annotation signature.
 *
 * Used by `scry sequences` to build canonical bracketing patterns.
 * Classification uses priority-ordered rules (first match wins).
 */

import type { GsmSummary } from "./games";

export type GsmRole =
  | "CAST"
  | "CAST_TARGETED"
  | "TARGETS_CONFIRMED"
  | "RESOLVE"
  | "RESOLVE_KILL"
  | "COMBAT_DAMAGE"
  | "COMBAT_DAMAGE_KILL"
  | "LAND"
  | "DRAW"
  | "PHASE"
  | "TRIGGER_ENTER"
  | "TRIGGER_RESOLVE"
  | "MANA_BRACKET"
  | "ECHO"
  | "UNKNOWN";

/** Classify a GSM by its transient annotation signature. */
export function classifyGsm(gsm: GsmSummary): GsmRole {
  const anns: any[] = gsm.raw.annotations ?? [];
  if (anns.length === 0) return "ECHO";

  const types = new Set<string>();
  for (const ann of anns) {
    for (const t of ann.type ?? []) {
      types.add(t.replace("AnnotationType_", ""));
    }
  }

  const hasZtCast = hasZtCategory(anns, "CastSpell");
  const hasZtCastByZone = !hasZtCast && hasZtFromHandToStack(anns);
  const castSignal = hasZtCast || hasZtCastByZone;
  const hasPST = types.has("PlayerSelectingTargets");
  const hasPSuT = types.has("PlayerSubmittedTargets");
  const hasResStart = types.has("ResolutionStart");
  const hasResComplete = types.has("ResolutionComplete");
  const hasManaPaid = types.has("ManaPaid");
  const hasAIC = types.has("AbilityInstanceCreated");
  const hasDamageDealt = types.has("DamageDealt");
  const hasZtPlayLand = hasZtCategory(anns, "PlayLand") || hasZtFromHandToBattlefield(anns);
  const hasZtDraw = hasZtCategory(anns, "Draw") || hasZtFromLibraryToHand(anns);
  const hasZtSbaDeath = hasZtCategory(anns, "SBA_Damage") || hasZtCategory(anns, "SBA_ZeroToughness") || hasZtCategory(anns, "Destroy");

  // Rule 1: zero transient annotations (already handled above, but guard)
  if (types.size === 0) return "ECHO";

  // Rule 2: Cast targeted
  if (castSignal && hasPST) return "CAST_TARGETED";

  // Rule 3: Cast untargeted
  if (castSignal && !hasPST) return "CAST";

  // Rule 4: Targets confirmed
  if (hasPSuT) return "TARGETS_CONFIRMED";

  // Rule 5: Resolve with kill
  if (hasResStart && hasResComplete && hasZtSbaDeath) return "RESOLVE_KILL";

  // Rule 6: Resolve
  if (hasResStart && hasResComplete) return "RESOLVE";

  // Rule 7: Combat damage with kill
  if (hasDamageDealt && isCombatPhase(gsm) && hasZtSbaDeath) return "COMBAT_DAMAGE_KILL";

  // Rule 8: Combat damage
  if (hasDamageDealt && isCombatPhase(gsm)) return "COMBAT_DAMAGE";

  // Rule 9: Land play
  if (hasZtPlayLand) return "LAND";

  // Rule 10: Draw
  if (hasZtDraw) return "DRAW";

  // Rule 11: Trigger enter (AIC without ManaPaid)
  if (hasAIC && !hasManaPaid) return "TRIGGER_ENTER";

  // Rule 12: Mana bracket
  if (hasManaPaid) return "MANA_BRACKET";

  // Rule 13: Phase only
  if (types.size === 1 && types.has("PhaseOrStepModified")) return "PHASE";

  // Rule 14: fallback
  return "UNKNOWN";
}

// --- Helpers ---

function isCombatPhase(gsm: GsmSummary): boolean {
  return gsm.phase.includes("Combat");
}

/** Check if any ZoneTransfer annotation has a specific category in its details. */
function hasZtCategory(anns: any[], category: string): boolean {
  for (const ann of anns) {
    const types: string[] = ann.type ?? [];
    if (!types.some((t: string) => t.includes("ZoneTransfer"))) continue;
    for (const d of ann.details ?? []) {
      if (d.key === "category") {
        const vals: string[] = d.valueString ?? [];
        if (vals.some((v: string) => v.includes(category))) return true;
      }
    }
  }
  return false;
}

/** Fallback: Hand → Stack implies CastSpell. */
function hasZtFromHandToStack(anns: any[]): boolean {
  return hasZtZonePair(anns, "Hand", "Stack");
}

/** Fallback: Hand → Battlefield implies PlayLand. */
function hasZtFromHandToBattlefield(anns: any[]): boolean {
  return hasZtZonePair(anns, "Hand", "Battlefield");
}

/** Fallback: Library → Hand implies Draw. */
function hasZtFromLibraryToHand(anns: any[]): boolean {
  return hasZtZonePair(anns, "Library", "Hand");
}

/** Check ZoneTransfer by zone_src/zone_dest detail values. */
function hasZtZonePair(anns: any[], srcType: string, destType: string): boolean {
  for (const ann of anns) {
    const types: string[] = ann.type ?? [];
    if (!types.some((t: string) => t.includes("ZoneTransfer"))) continue;
    let src = false, dest = false;
    for (const d of ann.details ?? []) {
      // zone_src and zone_dest are zone IDs (ints), not type names.
      // We can't resolve zone IDs without the accumulator, so this fallback
      // only works when category is a string. Skip if zone detail is numeric.
      if (d.key === "zone_src" && d.valueString?.some((v: string) => v.includes(srcType))) src = true;
      if (d.key === "zone_dest" && d.valueString?.some((v: string) => v.includes(destType))) dest = true;
    }
    if (src && dest) return true;
  }
  return false;
}

/**
 * Extract the ZoneTransfer category string from an annotation.
 * Returns null if no category found.
 */
export function getZtCategory(ann: any): string | null {
  for (const d of ann.details ?? []) {
    if (d.key === "category") {
      const vals: string[] = d.valueString ?? [];
      return vals[0]?.replace("ZoneTransferCategory_", "") ?? null;
    }
  }
  return null;
}

/**
 * Extract instanceId from a GSM's annotations based on role.
 * Used for linking start/end anchors in interaction detection.
 */
export function extractInstanceId(gsm: GsmSummary, role: GsmRole): number | null {
  const anns: any[] = gsm.raw.annotations ?? [];

  switch (role) {
    case "CAST":
    case "CAST_TARGETED": {
      // Spell instanceId from ZoneTransfer(CastSpell) affectedIds[0]
      for (const ann of anns) {
        if (!(ann.type ?? []).some((t: string) => t.includes("ZoneTransfer"))) continue;
        const cat = getZtCategory(ann);
        if (cat && cat.includes("CastSpell")) {
          return ann.affectedIds?.[0] ?? null;
        }
      }
      // Fallback: first ZT affectedIds
      for (const ann of anns) {
        if ((ann.type ?? []).some((t: string) => t.includes("ZoneTransfer"))) {
          return ann.affectedIds?.[0] ?? null;
        }
      }
      return null;
    }
    case "RESOLVE":
    case "RESOLVE_KILL": {
      // Spell instanceId from ResolutionStart affectorId
      for (const ann of anns) {
        if ((ann.type ?? []).some((t: string) => t.includes("ResolutionStart"))) {
          return ann.affectorId ?? null;
        }
      }
      return null;
    }
    case "TRIGGER_ENTER": {
      // Ability instanceId from AbilityInstanceCreated affectedIds[0]
      for (const ann of anns) {
        if ((ann.type ?? []).some((t: string) => t.includes("AbilityInstanceCreated"))) {
          return ann.affectedIds?.[0] ?? null;
        }
      }
      return null;
    }
    case "TRIGGER_RESOLVE": {
      // Check ResolutionStart affectorId first, then AID affectedIds
      for (const ann of anns) {
        if ((ann.type ?? []).some((t: string) => t.includes("ResolutionStart"))) {
          return ann.affectorId ?? null;
        }
      }
      for (const ann of anns) {
        if ((ann.type ?? []).some((t: string) => t.includes("AbilityInstanceDeleted"))) {
          return ann.affectedIds?.[0] ?? null;
        }
      }
      return null;
    }
    default:
      return null;
  }
}

/**
 * Extract ObjectIdChanged mappings from a GSM's annotations.
 * Returns array of [origId, newId] pairs.
 */
export function extractIdChanges(gsm: GsmSummary): [number, number][] {
  const result: [number, number][] = [];
  for (const ann of gsm.raw.annotations ?? []) {
    if (!(ann.type ?? []).some((t: string) => t.includes("ObjectIdChanged"))) continue;
    let origId: number | null = null;
    let newId: number | null = null;
    for (const d of ann.details ?? []) {
      if (d.key === "orig_id") origId = d.valueInt32?.[0] ?? d.valueUint32?.[0] ?? null;
      if (d.key === "new_id") newId = d.valueInt32?.[0] ?? d.valueUint32?.[0] ?? null;
    }
    if (origId != null && newId != null) result.push([origId, newId]);
  }
  return result;
}
