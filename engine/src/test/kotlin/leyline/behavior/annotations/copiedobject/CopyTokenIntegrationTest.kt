package leyline.behavior.annotations.copiedobject

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.bootstrap.GameBootstrap
import leyline.game.codes.DetailKeys
import leyline.game.event.FrameEventLog
import leyline.game.snapshot.GsmSnapshot
import leyline.testkit.SessionTest
import leyline.testkit.TestCardRegistry
import leyline.testkit.detailInt
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import leyline.testkit.StateMapperShell as StateMapper

/**
 * Integration tests for the token identity subsystem:
 * - Copy token gets source grpId + isCopy flag
 * - Copy token fields survive diff GSMs
 * - TemporaryPermanent pAnn emitted for EOT-sacrifice copies
 * - TemporaryPermanent NOT emitted for permanent copies
 *
 * Board A: Electroduplicate (copy + haste + EOT sacrifice).
 * Board B: Homunculus Horde (permanent copy via draw trigger).
 */
class CopyTokenIntegrationTest :
    SessionTest({

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
            TestCardRegistry.ensureCardRegistered("Electroduplicate")
            TestCardRegistry.ensureCardRegistered("Grizzly Bears")
            TestCardRegistry.ensureCardRegistered("Mountain")
            TestCardRegistry.ensureCardRegistered("Homunculus Horde")
            TestCardRegistry.ensureCardRegistered("Quick Study")
            TestCardRegistry.ensureCardRegistered("Island")
        }

        // Board A: Electroduplicate targeting Grizzly Bears
        // Produces a copy token with haste + EOT sacrifice
        val puzzleText =
            """
            [metadata]
            Name:Copy Token Test
            Goal:Win
            Turns:5
            Difficulty:Easy
            Description:Cast Electroduplicate to create a copy of Grizzly Bears.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanhand=Electroduplicate
            humanbattlefield=Grizzly Bears;Mountain;Mountain;Mountain
            humanlibrary=Mountain;Mountain;Mountain;Mountain;Mountain
            aibattlefield=Mountain
            ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
            """.trimIndent()

        /**
         * Cast Electroduplicate, resolve, and return the copy token's Forge Card
         * plus its instanceId.
         */
        fun castAndResolveCopy(): Pair<forge.game.card.Card, Int> {
            // Preconditions
            human
                .getZone(ZoneType.Hand)
                .cards
                .any { it.name == "Electroduplicate" }
                .shouldBeTrue()
            val creatures = humanBattlefieldCreatures()
            creatures.shouldNotBeEmpty()
            val bearsIid = creatures.first { it.second == "Grizzly Bears" }.first

            // Cast Electroduplicate targeting Grizzly Bears
            castSpellByName("Electroduplicate").shouldBeTrue()
            selectTargets(listOf(bearsIid))

            // Pass priority until copy token appears
            repeat(10) {
                val tokens = human.getZone(ZoneType.Battlefield).cards.filter { it.isToken }
                if (tokens.isNotEmpty()) return@repeat
                passPriority()
            }

            val copyToken =
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .firstOrNull { it.isToken }
                    .shouldNotBeNull()
            val copyIid = human.battlefield.iid(copyToken)

            return copyToken to copyIid
        }

        session("copy token gets source grpId, isCopy, and objectSourceGrpId", puzzle = puzzleText, validating = true) {
            val (copyToken, copyIid) = castAndResolveCopy()

            // Forge should mark this as a copy
            copyToken.copiedPermanent.shouldNotBeNull()
            copyToken.isToken.shouldBeTrue()

            // Build GSM and find the copy token object
            val snapCopy1 = GsmSnapshot.capture(game(), bridge, "test-copy", 1)
            val gsm =
                StateMapper
                    .buildFromSnapshot(
                        snapCopy1,
                        1,
                        "test-copy",
                        bridge,
                        viewingSeatId = 1,
                        effectFacts = bridge.materializeEffectProjectionFacts(),
                        abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
                    ).gsm
            val copyObj =
                gsm.gameObjectsList
                    .firstOrNull { it.instanceId == copyIid }
                    .shouldNotBeNull()

            // Source grpId — should match Grizzly Bears, not a token grpId
            val bearsGrpId = bridge.cardRepository.findGrpIdByName("Grizzly Bears")!!
            assertSoftly {
                copyObj.grpId shouldBe bearsGrpId
                copyObj.overlayGrpId shouldBe bearsGrpId
                copyObj.objectSourceGrpId shouldBe bearsGrpId
                copyObj.isCopy shouldBe true
            }
        }

        session("copy token retains cardTypes and power/toughness in GSM", puzzle = puzzleText, validating = true) {
            val (_, copyIid) = castAndResolveCopy()

            val snapCopy2 = GsmSnapshot.capture(game(), bridge, "test-copy", 1)
            val gsm =
                StateMapper
                    .buildFromSnapshot(
                        snapCopy2,
                        1,
                        "test-copy",
                        bridge,
                        viewingSeatId = 1,
                        effectFacts = bridge.materializeEffectProjectionFacts(),
                        abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
                    ).gsm
            val copyObj = gsm.gameObjectsList.first { it.instanceId == copyIid }

            assertSoftly {
                copyObj.cardTypesList.shouldNotBeEmpty()
                copyObj.cardTypesList shouldContain wotc.mtgo.gre.external.messaging.Messages.CardType.Creature
                copyObj.power.value shouldBe 2
                copyObj.toughness.value shouldBe 2
            }
        }

        session("copy token fields survive diff GSM", puzzle = puzzleText, validating = true) {
            val (_, copyIid) = castAndResolveCopy()

            // First GSM — establishes baseline (apply mutations so recordZone fires)
            val snapCopy3 = GsmSnapshot.capture(game(), bridge, "test-copy", 1)
            val baselineResult =
                StateMapper.buildFromSnapshot(
                    snapCopy3,
                    1,
                    "test-copy",
                    bridge,
                    viewingSeatId = 1,
                    effectFacts = bridge.materializeEffectProjectionFacts(),
                    abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
                )
            bridge.commitProjection(baselineResult.transition)

            // Trigger a state change (pass priority) so a diff is generated
            passPriority()

            // Second GSM — diff from baseline
            val snapCopy4 = GsmSnapshot.capture(game(), bridge, "test-copy", 2)
            val gsm2 =
                StateMapper
                    .buildDiff(
                        snapCopy3,
                        snapCopy4,
                        FrameEventLog.EMPTY,
                        2,
                        "test-copy",
                        bridge,
                        viewingSeatId = 1,
                        effectFacts = bridge.materializeEffectProjectionFacts(),
                        abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
                    ).gsm

            // If the copy token appears in the diff, its fields must be intact
            val copyInDiff = gsm2.gameObjectsList.firstOrNull { it.instanceId == copyIid }
            if (copyInDiff != null) {
                val bearsGrpId = bridge.cardRepository.findGrpIdByName("Grizzly Bears")!!
                assertSoftly {
                    copyInDiff.grpId shouldBe bearsGrpId
                    copyInDiff.isCopy shouldBe true
                    copyInDiff.cardTypesList.shouldNotBeEmpty()
                }
            }
            // If absent from diff, it means unchanged — equally valid (no field stripping)

            // Registry should have cached the grpId
            bridge.tokenRegistry
                .resolve(copyIid)
                .shouldNotBeNull()
        }

        session("TemporaryPermanent pAnn emitted for EOT-sacrifice copy", puzzle = puzzleText, validating = true) {
            val (copyToken, copyIid) = castAndResolveCopy()

            // Electroduplicate adds EndOfTurnLeavePlay SVar
            copyToken.hasSVar("EndOfTurnLeavePlay").shouldBeTrue()

            // Build GSM — should have TemporaryPermanent persistent annotation
            val snapCopy4 = GsmSnapshot.capture(game(), bridge, "test-copy", 1)
            val gsm =
                StateMapper
                    .buildFromSnapshot(
                        snapCopy4,
                        1,
                        "test-copy",
                        bridge,
                        viewingSeatId = 1,
                        effectFacts = bridge.materializeEffectProjectionFacts(),
                        abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
                    ).gsm
            val tempPerm =
                gsm.persistentAnnotationsList
                    .firstOrNull { ann ->
                        ann.typeList.contains(AnnotationType.TemporaryPermanent) &&
                            ann.affectorId == copyIid
                    }.shouldNotBeNull()

            tempPerm.affectedIdsList shouldContain copyIid
            tempPerm.detailInt(DetailKeys.ABILITY_GRP_ID_UPPER) shouldBe 192424
        }

        // --- Board B: Homunculus Horde (permanent copy, no EOT sacrifice) ---

        // Quick Study draws 2 cards — guarantees both draws happen from one spell,
        // firing the "second card drawn" trigger regardless of draw-step state.
        val homunculusPuzzle =
            """
            [metadata]
            Name:Homunculus Horde Copy
            Goal:Win
            Turns:5
            Difficulty:Easy
            Description:Cast Quick Study to trigger Homunculus Horde copy.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanhand=Quick Study
            humanbattlefield=Homunculus Horde;Island;Island;Island
            humanlibrary=Island;Island;Island;Island;Island;Island;Island;Island
            aibattlefield=Island
            ailibrary=Island;Island;Island;Island;Island
            """.trimIndent()

        fun castQuickStudyAndWaitForCopy(): Pair<forge.game.card.Card, Int> {
            assertSoftly {
                human
                    .getZone(ZoneType.Hand)
                    .cards
                    .map { it.name } shouldContain "Quick Study"
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .map { it.name } shouldContain "Homunculus Horde"
            }

            castSpellByName("Quick Study").shouldBeTrue()

            // Pass until a token copy appears
            repeat(15) {
                val tokens = human.getZone(ZoneType.Battlefield).cards.filter { it.isToken }
                if (tokens.isNotEmpty()) return@repeat
                passPriority()
            }

            val copyToken =
                human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .firstOrNull { it.isToken }
                    .shouldNotBeNull()
            val copyIid = human.battlefield.iid(copyToken)
            return copyToken to copyIid
        }

        session("Homunculus Horde copy gets source grpId and isCopy", puzzle = homunculusPuzzle, validating = true) {
            val (copyToken, copyIid) = castQuickStudyAndWaitForCopy()

            copyToken.copiedPermanent.shouldNotBeNull()
            copyToken.isToken.shouldBeTrue()

            val snapHom1 = GsmSnapshot.capture(game(), bridge, "test-homunculus", 1)
            val gsm =
                StateMapper
                    .buildFromSnapshot(
                        snapHom1,
                        1,
                        "test-homunculus",
                        bridge,
                        viewingSeatId = 1,
                        effectFacts = bridge.materializeEffectProjectionFacts(),
                        abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
                    ).gsm
            val copyObj =
                gsm.gameObjectsList
                    .firstOrNull { it.instanceId == copyIid }
                    .shouldNotBeNull()

            val hordeGrpId = bridge.cardRepository.findGrpIdByName("Homunculus Horde")!!
            assertSoftly {
                copyObj.grpId shouldBe hordeGrpId
                copyObj.overlayGrpId shouldBe hordeGrpId
                copyObj.objectSourceGrpId shouldBe hordeGrpId
                copyObj.isCopy shouldBe true
                copyObj.cardTypesList shouldContain wotc.mtgo.gre.external.messaging.Messages.CardType.Creature
                copyObj.power.value shouldBe 2
                copyObj.toughness.value shouldBe 2
            }
        }

        session("Homunculus Horde copy has NO TemporaryPermanent pAnn", puzzle = homunculusPuzzle, validating = true) {
            val (copyToken, copyIid) = castQuickStudyAndWaitForCopy()

            // Permanent copy — no EOT sacrifice SVar
            copyToken.hasSVar("EndOfTurnLeavePlay") shouldBe false

            val snapHom2 = GsmSnapshot.capture(game(), bridge, "test-homunculus", 1)
            val gsm =
                StateMapper
                    .buildFromSnapshot(
                        snapHom2,
                        1,
                        "test-homunculus",
                        bridge,
                        viewingSeatId = 1,
                        effectFacts = bridge.materializeEffectProjectionFacts(),
                        abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
                    ).gsm
            val tempPerm =
                gsm.persistentAnnotationsList
                    .firstOrNull { ann ->
                        ann.typeList.contains(AnnotationType.TemporaryPermanent) &&
                            ann.affectorId == copyIid
                    }
            tempPerm.shouldBeNull()
        }
    })
