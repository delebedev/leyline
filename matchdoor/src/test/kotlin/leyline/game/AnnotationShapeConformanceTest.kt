package leyline.game

import io.kotest.assertions.fail
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo

/**
 * Annotation detail-key shape conformance tests.
 *
 * Two concerns:
 * 1. **Per-builder shape tests** — assert each builder produces the exact
 *    set of detail keys the client expects.
 * 2. **Golden reference conformance** — cross-check all builders against
 *    always-present keys from real Arena server recordings
 *    (`just proto-annotation-variance`).
 *
 * See also: `AnnotationBuilderTest` for per-field value/type assertions.
 */
class AnnotationShapeConformanceTest :
    FunSpec({

        tags(UnitTag)

        fun detailKeys(ann: AnnotationInfo): Set<String> =
            ann.detailsList.map { it.key }.toSet()

        // =======================================================================
        // Per-builder detail-key shape tests
        //
        // Verify each builder method produces the exact set of detail keys
        // the real Arena server sends (from golden recording reference).
        // =======================================================================

        test("DamageDealt shape: {damage, type, markDamage} — matches golden combat-damage.bin gsId=126") {
            val ann = AnnotationBuilder.damageDealt(sourceInstanceId = 1, amount = 3)
            detailKeys(ann) shouldBe setOf("damage", "type", "markDamage")
        }

        test("ManaPaid shape: {id, color} — matches golden stack-resolve.bin gsId=66") {
            val ann = AnnotationBuilder.manaPaid(instanceId = 1, manaId = 1, color = "Green")
            detailKeys(ann) shouldBe setOf("id", "color")
        }

        test("AbilityInstanceCreated shape: {source_zone} — matches golden stack-resolve.bin gsId=66") {
            val ann = AnnotationBuilder.abilityInstanceCreated(instanceId = 1, sourceZoneId = 31)
            detailKeys(ann) shouldBe setOf("source_zone")
        }

        test("ZoneTransfer shape: {zone_src, zone_dest, category}") {
            val ann = AnnotationBuilder.zoneTransfer(1, 31, 28, "PlayLand")
            detailKeys(ann) shouldBe setOf("zone_src", "zone_dest", "category")
        }

        test("ResolutionStart shape: {grpid}") {
            val ann = AnnotationBuilder.resolutionStart(1, 12345)
            detailKeys(ann) shouldBe setOf("grpid")
        }

        test("ResolutionComplete shape: {grpid}") {
            val ann = AnnotationBuilder.resolutionComplete(1, 12345)
            detailKeys(ann) shouldBe setOf("grpid")
        }

        test("UserActionTaken shape: {actionType, abilityGrpId}") {
            val ann = AnnotationBuilder.userActionTaken(1, 1, 1, 0)
            detailKeys(ann) shouldBe setOf("actionType", "abilityGrpId")
        }

        test("TappedUntappedPermanent shape: {tapped}") {
            val ann = AnnotationBuilder.tappedUntappedPermanent(1, 2)
            detailKeys(ann) shouldBe setOf("tapped")
        }

        test("ObjectIdChanged shape: {orig_id, new_id}") {
            val ann = AnnotationBuilder.objectIdChanged(1, 2)
            detailKeys(ann) shouldBe setOf("orig_id", "new_id")
        }

        test("PhaseOrStepModified shape: {phase, step}") {
            val ann = AnnotationBuilder.phaseOrStepModified(1, 1, 2)
            detailKeys(ann) shouldBe setOf("phase", "step")
        }

        test("ModifiedLife shape: {delta}") {
            val ann = AnnotationBuilder.modifiedLife(1, -3)
            detailKeys(ann) shouldBe setOf("life")
        }

        test("ModifiedPower shape: no required keys") {
            val ann = AnnotationBuilder.modifiedPower(1)
            detailKeys(ann) shouldBe emptySet()
        }

        test("ModifiedToughness shape: no required keys") {
            val ann = AnnotationBuilder.modifiedToughness(1)
            detailKeys(ann) shouldBe emptySet()
        }

        test("LossOfGame shape: {reason}") {
            val ann = AnnotationBuilder.lossOfGame(1, 0)
            detailKeys(ann) shouldBe setOf("reason")
        }

        test("CounterAdded shape: {counter_type, transaction_amount}") {
            val ann = AnnotationBuilder.counterAdded(1, "P1P1", 2)
            detailKeys(ann) shouldBe setOf("counter_type", "transaction_amount")
        }

        test("CounterRemoved shape: {counter_type, transaction_amount}") {
            val ann = AnnotationBuilder.counterRemoved(1, "LOYALTY", 1)
            detailKeys(ann) shouldBe setOf("counter_type", "transaction_amount")
        }

        test("Scry shape: {topCount, bottomCount}") {
            val ann = AnnotationBuilder.scry(1, 2, 1)
            detailKeys(ann) shouldBe setOf("topCount", "bottomCount")
        }

        test("SyntheticEvent shape: {type}") {
            val ann = AnnotationBuilder.syntheticEvent(1)
            detailKeys(ann) shouldBe setOf("type")
        }

        test("Counter shape: {count, counter_type}") {
            val ann = AnnotationBuilder.counter(1, 1, 1)
            detailKeys(ann) shouldBe setOf("count", "counter_type")
        }

        test("AddAbility shape: {grpid, effect_id, UniqueAbilityId, originalAbilityObjectZcid}") {
            detailKeys(AnnotationBuilder.addAbility(1, 1, 1, 1, 1)) shouldBe
                setOf("grpid", "effect_id", "UniqueAbilityId", "originalAbilityObjectZcid")
        }

        test("RemoveAbility shape: {effect_id}") {
            detailKeys(AnnotationBuilder.removeAbility(1, 1)) shouldBe setOf("effect_id")
        }

        test("AbilityExhausted shape: {AbilityGrpId, UsesRemaining, UniqueAbilityId}") {
            detailKeys(AnnotationBuilder.abilityExhausted(1, 1, 0, 1)) shouldBe
                setOf("AbilityGrpId", "UsesRemaining", "UniqueAbilityId")
        }

        test("GainDesignation shape: {DesignationType}") {
            detailKeys(AnnotationBuilder.gainDesignation(1, 19)) shouldBe setOf("DesignationType")
        }

        test("Designation shape: {DesignationType}") {
            detailKeys(AnnotationBuilder.designation(1, 19)) shouldBe setOf("DesignationType")
        }

        test("LayeredEffect shape: {effect_id}") {
            detailKeys(AnnotationBuilder.layeredEffect(1, 7004)) shouldBe setOf("effect_id")
        }

        test("ColorProduction shape: {colors}") {
            detailKeys(AnnotationBuilder.colorProduction(1, listOf(1))) shouldBe setOf("colors")
        }

        test("TriggeringObject shape: {source_zone}") {
            detailKeys(AnnotationBuilder.triggeringObject(1, 27)) shouldBe setOf("source_zone")
        }

        test("TargetSpec shape: {abilityGrpId, index, promptId, promptParameters}") {
            detailKeys(AnnotationBuilder.targetSpec(1, 1, 1, 1, 1)) shouldBe
                setOf("abilityGrpId", "index", "promptId", "promptParameters")
        }

        test("PowerToughnessModCreated shape: {power, toughness}") {
            detailKeys(AnnotationBuilder.powerToughnessModCreated(1, 1, 1)) shouldBe setOf("power", "toughness")
        }

        test("DisplayCardUnderCard shape: {Disable, TemporaryZoneTransfer}") {
            detailKeys(AnnotationBuilder.displayCardUnderCard(1)) shouldBe setOf("Disable", "TemporaryZoneTransfer")
        }

        test("PredictedDirectDamage shape: {value}") {
            detailKeys(AnnotationBuilder.predictedDirectDamage(1, 1)) shouldBe setOf("value")
        }

        test("No-detail annotations: NewTurnStarted, EnteredZoneThisTurn, etc.") {
            detailKeys(AnnotationBuilder.newTurnStarted(1)) shouldBe emptySet()
            detailKeys(AnnotationBuilder.enteredZoneThisTurn(28, 1)) shouldBe emptySet()
            detailKeys(AnnotationBuilder.abilityInstanceDeleted(1)) shouldBe emptySet()
            detailKeys(AnnotationBuilder.tokenCreated(1)) shouldBe emptySet()
            detailKeys(AnnotationBuilder.tokenDeleted(1)) shouldBe emptySet()
            detailKeys(AnnotationBuilder.attachmentCreated(1, 2)) shouldBe emptySet()
            detailKeys(AnnotationBuilder.attachment(1, 2)) shouldBe emptySet()
            detailKeys(AnnotationBuilder.removeAttachment(1)) shouldBe emptySet()
            detailKeys(AnnotationBuilder.shuffle(1)) shouldBe emptySet()
            detailKeys(AnnotationBuilder.revealedCardCreated(1)) shouldBe emptySet()
            detailKeys(AnnotationBuilder.revealedCardDeleted(1)) shouldBe emptySet()
            detailKeys(AnnotationBuilder.layeredEffectDestroyed(1)) shouldBe emptySet()
            detailKeys(AnnotationBuilder.playerSelectingTargets(1)) shouldBe emptySet()
            detailKeys(AnnotationBuilder.playerSubmittedTargets(1)) shouldBe emptySet()
            detailKeys(AnnotationBuilder.damagedThisTurn(1)) shouldBe emptySet()
            detailKeys(AnnotationBuilder.instanceRevealedToOpponent(1)) shouldBe emptySet()
        }

        // =======================================================================
        // Golden reference conformance
        //
        // Always-present detail keys from real Arena server, extracted by
        // `just proto-annotation-variance` across 14 proxy sessions / 1898
        // annotation instances. This test fails on ANY change to our builder
        // output vs the golden reference, forcing triage.
        //
        // Workflow after fixing a builder:
        //   1. Fix the builder method in AnnotationBuilder.kt
        //   2. Run `just test-gate` — this test fails
        //   3. Remove the type from expectedMismatch (or update goldenAlwaysKeys)
        //   4. Run `just proto-annotation-variance --summary` to confirm OK
        // =======================================================================

        /**
         * Golden reference: always-present detail keys per annotation type,
         * observed in 100% of instances across all proxy recordings.
         *
         * Source: `just proto-annotation-variance --summary` (2026-02-28,
         * 14 sessions, 1898 instances, 39 types).
         */
        val goldenAlwaysKeys: Map<String, Set<String>> = mapOf(
            // --- High-frequency types (>50 instances) ---
            "PhaseOrStepModified" to setOf("phase", "step"), // 449 instances, 3 sessions
            "ZoneTransfer" to setOf("category", "zone_dest", "zone_src"), // 181 instances
            "EnteredZoneThisTurn" to emptySet(), // 177 instances — persistent, no details
            "UserActionTaken" to setOf("abilityGrpId", "actionType"), // 153 instances
            "ObjectIdChanged" to setOf("new_id", "orig_id"), // 152 instances
            "TappedUntappedPermanent" to setOf("tapped"), // 148 instances
            "AbilityInstanceCreated" to setOf("source_zone"), // 102 instances
            "AbilityInstanceDeleted" to emptySet(), // 97 instances
            "ManaPaid" to setOf("color", "id"), // 77 instances
            "ResolutionComplete" to setOf("grpid"), // 54 instances
            "ResolutionStart" to setOf("grpid"), // 53 instances

            // --- Medium-frequency types (5-50 instances) ---
            "NewTurnStarted" to emptySet(), // 44 instances
            "DamageDealt" to setOf("damage", "markDamage", "type"), // 12 instances
            "ModifiedToughness" to emptySet(), // 10 instances — all detail keys are optional
            "ModifiedPower" to emptySet(), // 10 instances — all detail keys are optional
            "ModifiedLife" to setOf("life"), // 8 instances
            "SyntheticEvent" to setOf("type"), // 7 instances

            // --- Low-frequency types (1-5 instances) ---
            "TokenCreated" to emptySet(), // 4 instances
            "AttachmentCreated" to emptySet(), // 4 instances
            "Attachment" to emptySet(), // 4 instances
            "CounterAdded" to setOf("counter_type", "transaction_amount"), // 3 instances
            "TokenDeleted" to emptySet(), // 1 instance
            "Counter" to setOf("count", "counter_type"),
            "AddAbility" to setOf("grpid", "effect_id", "UniqueAbilityId", "originalAbilityObjectZcid"),
            "RemoveAbility" to setOf("effect_id"),
            "AbilityExhausted" to setOf("AbilityGrpId", "UsesRemaining", "UniqueAbilityId"),
            "GainDesignation" to setOf("DesignationType"),
            "Designation" to setOf("DesignationType"),
            "LayeredEffect" to setOf("effect_id"),
            "LayeredEffectDestroyed" to emptySet(),
            "PlayerSelectingTargets" to emptySet(),
            "PlayerSubmittedTargets" to emptySet(),
            "DamagedThisTurn" to emptySet(),
            "InstanceRevealedToOpponent" to emptySet(),
            "ColorProduction" to setOf("colors"),
            "TriggeringObject" to setOf("source_zone"),
            "TargetSpec" to setOf("abilityGrpId", "index", "promptId", "promptParameters"),
            "PowerToughnessModCreated" to setOf("power", "toughness"),
            "DisplayCardUnderCard" to setOf("Disable", "TemporaryZoneTransfer"),
            "PredictedDirectDamage" to setOf("value"),
        )

        /**
         * Our builder output per type — calls each builder with dummy args,
         * extracts detail keys.
         */
        val ourBuilderKeys: Map<String, Set<String>> = mapOf(
            "PhaseOrStepModified" to detailKeys(AnnotationBuilder.phaseOrStepModified(1, 1, 2)),
            "ZoneTransfer" to detailKeys(AnnotationBuilder.zoneTransfer(1, 31, 28, "PlayLand")),
            "EnteredZoneThisTurn" to detailKeys(AnnotationBuilder.enteredZoneThisTurn(28, 1)),
            "UserActionTaken" to detailKeys(AnnotationBuilder.userActionTaken(1, 1, 1, 0)),
            "ObjectIdChanged" to detailKeys(AnnotationBuilder.objectIdChanged(1, 2)),
            "TappedUntappedPermanent" to detailKeys(AnnotationBuilder.tappedUntappedPermanent(1, 2)),
            "AbilityInstanceCreated" to detailKeys(AnnotationBuilder.abilityInstanceCreated(1, 31)),
            "AbilityInstanceDeleted" to detailKeys(AnnotationBuilder.abilityInstanceDeleted(1)),
            "ManaPaid" to detailKeys(AnnotationBuilder.manaPaid(1, 1, "Green")),
            "ResolutionComplete" to detailKeys(AnnotationBuilder.resolutionComplete(1, 1)),
            "ResolutionStart" to detailKeys(AnnotationBuilder.resolutionStart(1, 1)),
            "NewTurnStarted" to detailKeys(AnnotationBuilder.newTurnStarted(1)),
            "DamageDealt" to detailKeys(AnnotationBuilder.damageDealt(1, 3)),
            "ModifiedToughness" to detailKeys(AnnotationBuilder.modifiedToughness(1)),
            "ModifiedPower" to detailKeys(AnnotationBuilder.modifiedPower(1)),
            "ModifiedLife" to detailKeys(AnnotationBuilder.modifiedLife(1, -3)),
            "SyntheticEvent" to detailKeys(AnnotationBuilder.syntheticEvent(1)),
            "TokenCreated" to detailKeys(AnnotationBuilder.tokenCreated(1)),
            "AttachmentCreated" to detailKeys(AnnotationBuilder.attachmentCreated(1, 2)),
            "Attachment" to detailKeys(AnnotationBuilder.attachment(1, 2)),
            "CounterAdded" to detailKeys(AnnotationBuilder.counterAdded(1, "P1P1", 2)),
            "TokenDeleted" to detailKeys(AnnotationBuilder.tokenDeleted(1)),
            "Counter" to detailKeys(AnnotationBuilder.counter(1, 1, 1)),
            "AddAbility" to detailKeys(AnnotationBuilder.addAbility(1, 1, 1, 1, 1)),
            "RemoveAbility" to detailKeys(AnnotationBuilder.removeAbility(1, 1)),
            "AbilityExhausted" to detailKeys(AnnotationBuilder.abilityExhausted(1, 1, 0, 1)),
            "GainDesignation" to detailKeys(AnnotationBuilder.gainDesignation(1, 19)),
            "Designation" to detailKeys(AnnotationBuilder.designation(1, 19)),
            "LayeredEffect" to detailKeys(AnnotationBuilder.layeredEffect(1, 7004)),
            "LayeredEffectDestroyed" to detailKeys(AnnotationBuilder.layeredEffectDestroyed(1)),
            "PlayerSelectingTargets" to detailKeys(AnnotationBuilder.playerSelectingTargets(1)),
            "PlayerSubmittedTargets" to detailKeys(AnnotationBuilder.playerSubmittedTargets(1)),
            "DamagedThisTurn" to detailKeys(AnnotationBuilder.damagedThisTurn(1)),
            "InstanceRevealedToOpponent" to detailKeys(AnnotationBuilder.instanceRevealedToOpponent(1)),
            "ColorProduction" to detailKeys(AnnotationBuilder.colorProduction(1, listOf(1))),
            "TriggeringObject" to detailKeys(AnnotationBuilder.triggeringObject(1, 27)),
            "TargetSpec" to detailKeys(AnnotationBuilder.targetSpec(1, 1, 1, 1, 1)),
            "PowerToughnessModCreated" to detailKeys(AnnotationBuilder.powerToughnessModCreated(1, 1, 1)),
            "DisplayCardUnderCard" to detailKeys(AnnotationBuilder.displayCardUnderCard(1)),
            "PredictedDirectDamage" to detailKeys(AnnotationBuilder.predictedDirectDamage(1, 1)),
        )

        /**
         * Known mismatches: types where our builder intentionally differs from
         * the golden reference. Each entry documents WHY and what the fix looks like.
         *
         * When you fix a builder, REMOVE the entry here — the test will confirm
         * the fix by passing without it.
         */
        val expectedMismatch: Map<String, String> = emptyMap()

        test("Golden reference: our builder detail keys match real Arena server always-present keys") {
            val failures = mutableListOf<String>()

            for ((typeName, goldenKeys) in goldenAlwaysKeys) {
                val ourKeys = ourBuilderKeys[typeName]
                if (ourKeys == null) {
                    failures += "$typeName: no builder registered in ourBuilderKeys"
                    continue
                }

                val missing = goldenKeys - ourKeys // server sends, we don't
                val extra = ourKeys - goldenKeys // we send, server doesn't

                if (missing.isEmpty() && extra.isEmpty()) {
                    // OK — check it's NOT in expectedMismatch (stale entry)
                    if (typeName in expectedMismatch) {
                        failures += "$typeName: marked as expectedMismatch but now matches! " +
                            "Remove from expectedMismatch."
                    }
                    continue
                }

                // Mismatch — must be in expectedMismatch
                if (typeName !in expectedMismatch) {
                    failures += buildString {
                        append("$typeName: MISMATCH not in expectedMismatch.")
                        if (missing.isNotEmpty()) append(" missing=$missing")
                        if (extra.isNotEmpty()) append(" extra=$extra")
                        append(" Either fix the builder or add to expectedMismatch with a comment.")
                    }
                }
            }

            if (failures.isNotEmpty()) {
                val msg = buildString {
                    appendLine("Golden reference conformance failures:")
                    appendLine()
                    for (f in failures) appendLine("  - $f")
                    appendLine()
                    appendLine("Run `just proto-annotation-variance --summary` for current state.")
                }
                fail(msg)
            }
        }
    })
