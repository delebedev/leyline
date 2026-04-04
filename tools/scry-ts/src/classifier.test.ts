import { describe, test, expect } from "bun:test";
import { classifyGsm, type GsmRole } from "./classifier";
import type { GsmSummary } from "./games";

/** Build a minimal GsmSummary with annotation types and optional details. */
function synth(opts: {
  annotations?: any[];
  phase?: string;
  step?: string;
}): GsmSummary {
  const annotations = opts.annotations ?? [];
  const annotationTypes = [
    ...new Set(
      annotations.flatMap((a: any) =>
        (a.type ?? []).map((t: string) => t.replace("AnnotationType_", ""))
      )
    ),
  ];
  return {
    gsId: 1,
    type: "Diff",
    turn: 1,
    phase: opts.phase ?? "Main1",
    step: opts.step ?? "",
    activePlayer: 1,
    annotationCount: annotations.length,
    objectCount: 0,
    annotationTypes,
    raw: { annotations, zones: [], gameObjects: [] },
  } as unknown as GsmSummary;
}

/** Annotation helper with type and optional details. */
function ann(types: string[], details?: { key: string; valueString?: string[]; valueInt32?: number[] }[]): any {
  return {
    type: types.map((t) => `AnnotationType_${t}`),
    details: details ?? [],
    affectorId: 1,
    affectedIds: [2],
  };
}

/** ZoneTransfer annotation with category. */
function zt(category: string): any {
  return ann(["ZoneTransfer"], [{ key: "category", valueString: [category] }]);
}

describe("classifyGsm", () => {
  test("ECHO — zero annotations", () => {
    expect(classifyGsm(synth({}))).toBe("ECHO");
  });

  test("CAST_TARGETED — CastSpell + PlayerSelectingTargets", () => {
    expect(
      classifyGsm(synth({ annotations: [zt("CastSpell"), ann(["PlayerSelectingTargets"])] }))
    ).toBe("CAST_TARGETED");
  });

  test("CAST — CastSpell without PST", () => {
    expect(
      classifyGsm(synth({ annotations: [zt("CastSpell"), ann(["ManaPaid"])] }))
    ).toBe("CAST");
  });

  test("TARGETS_CONFIRMED — PlayerSubmittedTargets", () => {
    expect(
      classifyGsm(synth({ annotations: [ann(["PlayerSubmittedTargets"])] }))
    ).toBe("TARGETS_CONFIRMED");
  });

  test("RESOLVE_KILL — ResolutionStart + Complete + SBA death", () => {
    expect(
      classifyGsm(
        synth({
          annotations: [
            ann(["ResolutionStart"]),
            ann(["ResolutionComplete"]),
            zt("SBA_Damage"),
          ],
        })
      )
    ).toBe("RESOLVE_KILL");
  });

  test("RESOLVE — ResolutionStart + Complete", () => {
    expect(
      classifyGsm(
        synth({ annotations: [ann(["ResolutionStart"]), ann(["ResolutionComplete"])] })
      )
    ).toBe("RESOLVE");
  });

  test("COMBAT_DAMAGE_KILL — DamageDealt in combat phase + SBA death", () => {
    expect(
      classifyGsm(
        synth({
          annotations: [ann(["DamageDealt"]), zt("SBA_Damage")],
          phase: "Combat",
          step: "CombatDamage",
        })
      )
    ).toBe("COMBAT_DAMAGE_KILL");
  });

  test("COMBAT_DAMAGE — DamageDealt in combat phase", () => {
    expect(
      classifyGsm(
        synth({
          annotations: [ann(["DamageDealt"])],
          phase: "Combat",
          step: "CombatDamage",
        })
      )
    ).toBe("COMBAT_DAMAGE");
  });

  test("LAND — PlayLand category", () => {
    expect(classifyGsm(synth({ annotations: [zt("PlayLand")] }))).toBe("LAND");
  });

  test("DRAW — Draw category", () => {
    expect(classifyGsm(synth({ annotations: [zt("Draw")] }))).toBe("DRAW");
  });

  test("TRIGGER_ENTER — AIC without ManaPaid", () => {
    expect(
      classifyGsm(synth({ annotations: [ann(["AbilityInstanceCreated"])] }))
    ).toBe("TRIGGER_ENTER");
  });

  test("MANA_BRACKET — ManaPaid (without cast signal)", () => {
    expect(
      classifyGsm(synth({ annotations: [ann(["ManaPaid"]), ann(["AbilityInstanceCreated"])] }))
    ).toBe("MANA_BRACKET");
  });

  test("PHASE — PhaseOrStepModified only", () => {
    expect(
      classifyGsm(synth({ annotations: [ann(["PhaseOrStepModified"])] }))
    ).toBe("PHASE");
  });

  test("UNKNOWN — unrecognized annotation mix", () => {
    expect(
      classifyGsm(synth({ annotations: [ann(["SomeNewType"])] }))
    ).toBe("UNKNOWN");
  });

  // --- Priority edge cases ---

  test("CastSpell + ManaPaid + AIC → CAST (not MANA_BRACKET or TRIGGER_ENTER)", () => {
    expect(
      classifyGsm(
        synth({
          annotations: [zt("CastSpell"), ann(["ManaPaid"]), ann(["AbilityInstanceCreated"])],
        })
      )
    ).toBe("CAST");
  });

  test("CastSpell + PST + ManaPaid → CAST_TARGETED (not MANA_BRACKET)", () => {
    expect(
      classifyGsm(
        synth({
          annotations: [zt("CastSpell"), ann(["PlayerSelectingTargets"]), ann(["ManaPaid"])],
        })
      )
    ).toBe("CAST_TARGETED");
  });

  test("DamageDealt in Main1 → not combat damage → UNKNOWN", () => {
    expect(
      classifyGsm(synth({ annotations: [ann(["DamageDealt"])], phase: "Main1" }))
    ).toBe("UNKNOWN");
  });

  test("Resolve + ZT Destroy → RESOLVE_KILL", () => {
    expect(
      classifyGsm(
        synth({
          annotations: [
            ann(["ResolutionStart"]),
            ann(["ResolutionComplete"]),
            zt("Destroy"),
          ],
        })
      )
    ).toBe("RESOLVE_KILL");
  });
});
