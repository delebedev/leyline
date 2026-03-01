package forge.nexus.game

import org.testng.Assert.assertEquals
import org.testng.Assert.fail
import org.testng.annotations.Test
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
@Test(groups = ["unit"])
class AnnotationShapeConformanceTest {

    private fun detailKeys(ann: AnnotationInfo): Set<String> =
        ann.detailsList.map { it.key }.toSet()

    // =======================================================================
    // Per-builder detail-key shape tests
    //
    // Verify each builder method produces the exact set of detail keys
    // the real Arena server sends (from golden recording reference).
    // =======================================================================

    @Test(description = "DamageDealt shape: {damage, type, markDamage} — matches golden combat-damage.bin gsId=126")
    fun damageDealtDetailKeyShape() {
        val ann = AnnotationBuilder.damageDealt(sourceInstanceId = 1, amount = 3)
        assertEquals(
            detailKeys(ann),
            setOf("damage", "type", "markDamage"),
            "DamageDealt must have all three keys for combat damage animation",
        )
    }

    @Test(description = "ManaPaid shape: {id, color} — matches golden stack-resolve.bin gsId=66")
    fun manaPaidDetailKeyShape() {
        val ann = AnnotationBuilder.manaPaid(instanceId = 1, manaId = 1, color = "Green")
        assertEquals(
            detailKeys(ann),
            setOf("id", "color"),
            "ManaPaid must have id and color for mana payment tracking",
        )
    }

    @Test(description = "AbilityInstanceCreated shape: {source_zone} — matches golden stack-resolve.bin gsId=66")
    fun abilityInstanceCreatedDetailKeyShape() {
        val ann = AnnotationBuilder.abilityInstanceCreated(instanceId = 1, sourceZoneId = 31)
        assertEquals(
            detailKeys(ann),
            setOf("source_zone"),
            "AbilityInstanceCreated must have source_zone for animation origin",
        )
    }

    @Test(description = "ZoneTransfer shape: {zone_src, zone_dest, category}")
    fun zoneTransferDetailKeyShape() {
        val ann = AnnotationBuilder.zoneTransfer(1, 31, 28, "PlayLand")
        assertEquals(detailKeys(ann), setOf("zone_src", "zone_dest", "category"))
    }

    @Test(description = "ResolutionStart shape: {grpid}")
    fun resolutionStartDetailKeyShape() {
        val ann = AnnotationBuilder.resolutionStart(1, 12345)
        assertEquals(detailKeys(ann), setOf("grpid"))
    }

    @Test(description = "ResolutionComplete shape: {grpid}")
    fun resolutionCompleteDetailKeyShape() {
        val ann = AnnotationBuilder.resolutionComplete(1, 12345)
        assertEquals(detailKeys(ann), setOf("grpid"))
    }

    @Test(description = "UserActionTaken shape: {actionType, abilityGrpId}")
    fun userActionTakenDetailKeyShape() {
        val ann = AnnotationBuilder.userActionTaken(1, 1, 1, 0)
        assertEquals(detailKeys(ann), setOf("actionType", "abilityGrpId"))
    }

    @Test(description = "TappedUntappedPermanent shape: {tapped}")
    fun tappedUntappedDetailKeyShape() {
        val ann = AnnotationBuilder.tappedUntappedPermanent(1, 2)
        assertEquals(detailKeys(ann), setOf("tapped"))
    }

    @Test(description = "ObjectIdChanged shape: {orig_id, new_id}")
    fun objectIdChangedDetailKeyShape() {
        val ann = AnnotationBuilder.objectIdChanged(1, 2)
        assertEquals(detailKeys(ann), setOf("orig_id", "new_id"))
    }

    @Test(description = "PhaseOrStepModified shape: {phase, step}")
    fun phaseOrStepModifiedDetailKeyShape() {
        val ann = AnnotationBuilder.phaseOrStepModified(1, 1, 2)
        assertEquals(detailKeys(ann), setOf("phase", "step"))
    }

    @Test(description = "ModifiedLife shape: {delta}")
    fun modifiedLifeDetailKeyShape() {
        val ann = AnnotationBuilder.modifiedLife(1, -3)
        assertEquals(detailKeys(ann), setOf("life"))
    }

    @Test(description = "ModifiedPower shape: no required keys")
    fun modifiedPowerDetailKeyShape() {
        val ann = AnnotationBuilder.modifiedPower(1)
        assertEquals(detailKeys(ann), emptySet<String>())
    }

    @Test(description = "ModifiedToughness shape: no required keys")
    fun modifiedToughnessDetailKeyShape() {
        val ann = AnnotationBuilder.modifiedToughness(1)
        assertEquals(detailKeys(ann), emptySet<String>())
    }

    @Test(description = "LossOfGame shape: {reason}")
    fun lossOfGameDetailKeyShape() {
        val ann = AnnotationBuilder.lossOfGame(1, 0)
        assertEquals(detailKeys(ann), setOf("reason"))
    }

    @Test(description = "CounterAdded shape: {counter_type, transaction_amount}")
    fun counterAddedDetailKeyShape() {
        val ann = AnnotationBuilder.counterAdded(1, "P1P1", 2)
        assertEquals(detailKeys(ann), setOf("counter_type", "transaction_amount"))
    }

    @Test(description = "CounterRemoved shape: {counter_type, transaction_amount}")
    fun counterRemovedDetailKeyShape() {
        val ann = AnnotationBuilder.counterRemoved(1, "LOYALTY", 1)
        assertEquals(detailKeys(ann), setOf("counter_type", "transaction_amount"))
    }

    @Test(description = "Scry shape: {topCount, bottomCount}")
    fun scryDetailKeyShape() {
        val ann = AnnotationBuilder.scry(1, 2, 1)
        assertEquals(detailKeys(ann), setOf("topCount", "bottomCount"))
    }

    @Test(description = "SyntheticEvent shape: {type}")
    fun syntheticEventDetailKeyShape() {
        val ann = AnnotationBuilder.syntheticEvent(1)
        assertEquals(detailKeys(ann), setOf("type"))
    }

    @Test(description = "Counter shape: {count, counter_type}")
    fun counterDetailKeyShape() {
        val ann = AnnotationBuilder.counter(1, 1, 1)
        assertEquals(detailKeys(ann), setOf("count", "counter_type"))
    }

    @Test(description = "AddAbility shape: {grpid, effect_id, UniqueAbilityId, originalAbilityObjectZcid}")
    fun addAbilityDetailKeyShape() {
        assertEquals(
            detailKeys(AnnotationBuilder.addAbility(1, 1, 1, 1, 1)),
            setOf("grpid", "effect_id", "UniqueAbilityId", "originalAbilityObjectZcid"),
        )
    }

    @Test(description = "RemoveAbility shape: {effect_id}")
    fun removeAbilityDetailKeyShape() {
        assertEquals(detailKeys(AnnotationBuilder.removeAbility(1, 1)), setOf("effect_id"))
    }

    @Test(description = "AbilityExhausted shape: {AbilityGrpId, UsesRemaining, UniqueAbilityId}")
    fun abilityExhaustedDetailKeyShape() {
        assertEquals(
            detailKeys(AnnotationBuilder.abilityExhausted(1, 1, 0, 1)),
            setOf("AbilityGrpId", "UsesRemaining", "UniqueAbilityId"),
        )
    }

    @Test(description = "GainDesignation shape: {DesignationType}")
    fun gainDesignationDetailKeyShape() {
        assertEquals(detailKeys(AnnotationBuilder.gainDesignation(1, 19)), setOf("DesignationType"))
    }

    @Test(description = "Designation shape: {DesignationType}")
    fun designationDetailKeyShape() {
        assertEquals(detailKeys(AnnotationBuilder.designation(1, 19)), setOf("DesignationType"))
    }

    @Test(description = "LayeredEffect shape: {effect_id}")
    fun layeredEffectDetailKeyShape() {
        assertEquals(detailKeys(AnnotationBuilder.layeredEffect(1, 7004)), setOf("effect_id"))
    }

    @Test(description = "ColorProduction shape: {colors}")
    fun colorProductionDetailKeyShape() {
        assertEquals(detailKeys(AnnotationBuilder.colorProduction(1, 1)), setOf("colors"))
    }

    @Test(description = "TriggeringObject shape: {source_zone}")
    fun triggeringObjectDetailKeyShape() {
        assertEquals(detailKeys(AnnotationBuilder.triggeringObject(1, 27)), setOf("source_zone"))
    }

    @Test(description = "TargetSpec shape: {abilityGrpId, index, promptId, promptParameters}")
    fun targetSpecDetailKeyShape() {
        assertEquals(
            detailKeys(AnnotationBuilder.targetSpec(1, 1, 1, 1, 1)),
            setOf("abilityGrpId", "index", "promptId", "promptParameters"),
        )
    }

    @Test(description = "PowerToughnessModCreated shape: {power, toughness}")
    fun powerToughnessModCreatedDetailKeyShape() {
        assertEquals(detailKeys(AnnotationBuilder.powerToughnessModCreated(1, 1, 1)), setOf("power", "toughness"))
    }

    @Test(description = "DisplayCardUnderCard shape: {Disable, TemporaryZoneTransfer}")
    fun displayCardUnderCardDetailKeyShape() {
        assertEquals(detailKeys(AnnotationBuilder.displayCardUnderCard(1)), setOf("Disable", "TemporaryZoneTransfer"))
    }

    @Test(description = "PredictedDirectDamage shape: {value}")
    fun predictedDirectDamageDetailKeyShape() {
        assertEquals(detailKeys(AnnotationBuilder.predictedDirectDamage(1, 1)), setOf("value"))
    }

    @Test(description = "No-detail annotations: NewTurnStarted, EnteredZoneThisTurn, etc.")
    fun noDetailAnnotationShapes() {
        assertEquals(detailKeys(AnnotationBuilder.newTurnStarted(1)), emptySet<String>(), "NewTurnStarted")
        assertEquals(detailKeys(AnnotationBuilder.enteredZoneThisTurn(28, 1)), emptySet<String>(), "EnteredZoneThisTurn")
        assertEquals(detailKeys(AnnotationBuilder.abilityInstanceDeleted(1)), emptySet<String>(), "AbilityInstanceDeleted")
        assertEquals(detailKeys(AnnotationBuilder.tokenCreated(1)), emptySet<String>(), "TokenCreated")
        assertEquals(detailKeys(AnnotationBuilder.tokenDeleted(1)), emptySet<String>(), "TokenDeleted")
        assertEquals(detailKeys(AnnotationBuilder.attachmentCreated(1, 2)), emptySet<String>(), "AttachmentCreated")
        assertEquals(detailKeys(AnnotationBuilder.attachment(1, 2)), emptySet<String>(), "Attachment")
        assertEquals(detailKeys(AnnotationBuilder.removeAttachment(1)), emptySet<String>(), "RemoveAttachment")
        assertEquals(detailKeys(AnnotationBuilder.shuffle(1)), emptySet<String>(), "Shuffle")
        assertEquals(detailKeys(AnnotationBuilder.revealedCardCreated(1)), emptySet<String>(), "RevealedCardCreated")
        assertEquals(detailKeys(AnnotationBuilder.revealedCardDeleted(1)), emptySet<String>(), "RevealedCardDeleted")
        assertEquals(detailKeys(AnnotationBuilder.layeredEffectDestroyed(1)), emptySet<String>(), "LayeredEffectDestroyed")
        assertEquals(detailKeys(AnnotationBuilder.playerSelectingTargets(1)), emptySet<String>(), "PlayerSelectingTargets")
        assertEquals(detailKeys(AnnotationBuilder.playerSubmittedTargets(1)), emptySet<String>(), "PlayerSubmittedTargets")
        assertEquals(detailKeys(AnnotationBuilder.damagedThisTurn(1)), emptySet<String>(), "DamagedThisTurn")
        assertEquals(detailKeys(AnnotationBuilder.instanceRevealedToOpponent(1)), emptySet<String>(), "InstanceRevealedToOpponent")
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
    private val goldenAlwaysKeys: Map<String, Set<String>> = mapOf(
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
    private val ourBuilderKeys: Map<String, Set<String>> = mapOf(
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
        "ColorProduction" to detailKeys(AnnotationBuilder.colorProduction(1, 1)),
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
    private val expectedMismatch: Map<String, String> = emptyMap()

    @Test(description = "Golden reference: our builder detail keys match real Arena server always-present keys")
    fun goldenReferenceConformance() {
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
}
