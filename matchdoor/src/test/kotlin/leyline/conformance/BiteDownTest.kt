package leyline.conformance

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.IntegrationTag
import leyline.bridge.ForgeCardId
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import forge.game.zone.ZoneType as ForgeZoneType

/**
 * Integration test for Bite Down (grpId 93925).
 *
 * Wire spec: docs/card-specs/bite-down.md
 *
 * Key assertion: DamageDealt.affectorId = dealing creature iid (NOT the spell iid).
 * The annotation pipeline uses GameEventCardDamaged.source() which is the creature.
 *
 * Bite Down targeting: two sequential SelectTargetsReq groups.
 *   Group 1: "your creature" (the dealer) — human's Grizzly Bears
 *   Group 2: "opponent's creature/PW" (the target) — AI's Grizzly Bears
 * After both groups confirmed, autoPass resolves the spell inline.
 *
 * NOTE: The target creature's instanceId is reallocated by ObjectIdChanged
 * (SBA death reallocation). DamageDealt.affectedIds contains the NEW iid.
 */
class BiteDownTest :
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null

        afterEach {
            harness?.shutdown()
            harness = null
        }

        test("Bite Down: DamageDealt affectorId is the dealing creature, not the spell") {
            val h = MatchFlowHarness(seed = 42L, validating = false)
            harness = h
            h.connectAndKeepPuzzle("puzzles/bite-down.pzl")

            val human = h.game().registeredPlayers.first()
            val dealerCard = human.getZone(ForgeZoneType.Battlefield).cards
                .first { it.name == "Grizzly Bears" }
            val dealerIid = h.bridge.getOrAllocInstanceId(ForgeCardId(dealerCard.id)).value

            h.castSpellByName("Bite Down").shouldBeTrue()
            // Group 1: dealer creature, Group 2: target creature
            h.selectTargets(listOf(dealerIid))

            val ai = h.game().registeredPlayers.last()
            val targetCard = ai.getZone(ForgeZoneType.Battlefield).cards
                .first { it.name == "Grizzly Bears" }
            val targetIid = h.bridge.getOrAllocInstanceId(ForgeCardId(targetCard.id)).value
            h.selectTargets(listOf(targetIid))

            // Find DamageDealt annotation across all messages
            val damageAnn = h.allMessages
                .filter { it.hasGameStateMessage() }
                .flatMap { it.gameStateMessage.annotationsList }
                .firstOrNull { AnnotationType.DamageDealt_af5a in it.typeList }
            damageAnn.shouldNotBeNull()

            assertSoftly {
                // Core assertion: affectorId is the dealing creature, not the spell
                damageAnn.affectorId shouldBe dealerIid
                // Damage amount = dealer power (Grizzly Bears = 2)
                damageAnn.detailUint("damage") shouldBe 2
                // affectedIds contains the (possibly reallocated) target creature iid
                damageAnn.affectedIdsCount shouldBe 1
                damageAnn.getAffectedIds(0) shouldBeGreaterThan 0
            }

            h.accumulator.assertConsistent("after Bite Down resolution")
        }

        test("Bite Down: two TargetSpec pAnns on stack, removed on resolution") {
            val h = MatchFlowHarness(seed = 42L, validating = false)
            harness = h
            h.connectAndKeepPuzzle("puzzles/bite-down.pzl")

            val human = h.game().registeredPlayers.first()
            val dealerCard = human.getZone(ForgeZoneType.Battlefield).cards
                .first { it.name == "Grizzly Bears" }
            val dealerIid = h.bridge.getOrAllocInstanceId(ForgeCardId(dealerCard.id)).value

            val ai = h.game().registeredPlayers.last()
            val targetCard = ai.getZone(ForgeZoneType.Battlefield).cards
                .first { it.name == "Grizzly Bears" }
            val targetIid = h.bridge.getOrAllocInstanceId(ForgeCardId(targetCard.id)).value

            h.castSpellByName("Bite Down").shouldBeTrue()
            h.selectTargets(listOf(dealerIid))
            h.selectTargets(listOf(targetIid))

            // After targets submitted, TargetSpec pAnns should exist in some GSM
            val preResolve = h.allMessages
                .filter { it.hasGameStateMessage() }
                .flatMap { it.gameStateMessage.persistentAnnotationsList }
                .filter { AnnotationType.TargetSpec in it.typeList }
            preResolve.shouldHaveSize(2)

            // Group 1: dealer creature (index=1), Group 2: target creature (index=2)
            val group1 = preResolve.first { it.detailInt("index") == 1 }
            val group2 = preResolve.first { it.detailInt("index") == 2 }
            assertSoftly {
                // Two distinct targets
                group1.getAffectedIds(0) shouldNotBe group2.getAffectedIds(0)
                // abilityGrpId: same for both groups
                group1.detailInt("abilityGrpId") shouldBe group2.detailInt("abilityGrpId")
                group1.detailInt("abilityGrpId") shouldBeGreaterThan 0
                // promptParameters: spell ability instanceId (same for both)
                group1.detailInt("promptParameters") shouldBe group2.detailInt("promptParameters")
            }

            // After resolution, TargetSpec pAnns should be deleted on the next GSM.
            // Force a GSM build to trigger the upsert cleanup.
            h.passPriority()
            val allDeletedPannIds = h.allMessages
                .filter { it.hasGameStateMessage() }
                .flatMap { it.gameStateMessage.diffDeletedPersistentAnnotationIdsList }
                .toSet()
            val targetSpecIds = preResolve.map { it.id }.toSet()
            targetSpecIds.all { it in allDeletedPannIds }.shouldBeTrue()
        }

        test("Bite Down: target creature dies via Destroy zone transfer") {
            val h = MatchFlowHarness(seed = 42L, validating = false)
            harness = h
            h.connectAndKeepPuzzle("puzzles/bite-down.pzl")

            val human = h.game().registeredPlayers.first()
            val dealerCard = human.getZone(ForgeZoneType.Battlefield).cards
                .first { it.name == "Grizzly Bears" }
            val dealerIid = h.bridge.getOrAllocInstanceId(ForgeCardId(dealerCard.id)).value

            val ai = h.game().registeredPlayers.last()
            val targetCard = ai.getZone(ForgeZoneType.Battlefield).cards
                .first { it.name == "Grizzly Bears" }
            val targetIid = h.bridge.getOrAllocInstanceId(ForgeCardId(targetCard.id)).value

            h.castSpellByName("Bite Down").shouldBeTrue()
            h.selectTargets(listOf(dealerIid))
            h.selectTargets(listOf(targetIid))

            // Target creature is destroyed (Bite Down deals lethal damage)
            val destroyMsg = h.allMessages.firstWithTransferCategory("Destroy")
            destroyMsg.shouldNotBeNull()

            // Bite Down itself goes to GY
            val biteDownInGy = human.getZone(ForgeZoneType.Graveyard).cards
                .any { it.name == "Bite Down" }
            biteDownInGy.shouldBeTrue()

            // Target creature is in AI's graveyard
            val targetInGy = ai.getZone(ForgeZoneType.Graveyard).cards
                .any { it.name == "Grizzly Bears" }
            targetInGy.shouldBeTrue()
        }
    })
