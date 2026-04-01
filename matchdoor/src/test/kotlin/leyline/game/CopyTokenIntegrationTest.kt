package leyline.game

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.ForgeCardId
import leyline.bridge.GameBootstrap
import leyline.bridge.SeatId
import leyline.conformance.MatchFlowHarness
import leyline.conformance.TestCardRegistry
import leyline.conformance.detailInt
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Integration tests for the token identity subsystem:
 * - Copy token gets source grpId + isCopy flag
 * - Copy token fields survive diff GSMs
 * - TemporaryPermanent pAnn emitted for EOT-sacrifice copies
 * - TemporaryPermanent NOT emitted for permanent copies
 *
 * Uses Electroduplicate (copy + haste + EOT sacrifice) for Board A.
 * Homunculus Horde would test permanent copies but requires draw-count
 * trigger infrastructure — deferred until that mechanic is wired.
 */
class CopyTokenIntegrationTest :
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null
        afterEach {
            harness?.shutdown()
            harness = null
        }

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
            TestCardRegistry.ensureCardRegistered("Electroduplicate")
            TestCardRegistry.ensureCardRegistered("Grizzly Bears")
            TestCardRegistry.ensureCardRegistered("Mountain")
        }

        // Board A: Electroduplicate targeting Grizzly Bears
        // Produces a copy token with haste + EOT sacrifice
        val puzzleText = """
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
        fun castAndResolveCopy(h: MatchFlowHarness): Pair<forge.game.card.Card, Int> {
            val human = h.bridge.getPlayer(SeatId(1))!!

            // Preconditions
            human.getZone(ZoneType.Hand).cards.any { it.name == "Electroduplicate" }.shouldBeTrue()
            val creatures = h.humanBattlefieldCreatures()
            creatures.shouldNotBeEmpty()
            val bearsIid = creatures.first { it.second == "Grizzly Bears" }.first

            // Cast Electroduplicate targeting Grizzly Bears
            h.castSpellByName("Electroduplicate").shouldBeTrue()
            h.selectTargets(listOf(bearsIid))

            // Pass priority until copy token appears
            repeat(10) {
                val tokens = human.getZone(ZoneType.Battlefield).cards.filter { it.isToken }
                if (tokens.isNotEmpty()) return@repeat
                h.passPriority()
            }

            val copyToken = human.getZone(ZoneType.Battlefield).cards
                .firstOrNull { it.isToken }
                .shouldNotBeNull()
            val copyIid = h.bridge.getOrAllocInstanceId(ForgeCardId(copyToken.id)).value

            return copyToken to copyIid
        }

        test("copy token gets source grpId, isCopy, and objectSourceGrpId") {
            val h = MatchFlowHarness(seed = 42L, validating = false)
            harness = h
            h.connectAndKeepPuzzleText(puzzleText)

            val (copyToken, copyIid) = castAndResolveCopy(h)

            // Forge should mark this as a copy
            copyToken.copiedPermanent.shouldNotBeNull()
            copyToken.isToken.shouldBeTrue()

            // Build GSM and find the copy token object
            val gsm = StateMapper.buildFromGame(h.game(), 1, "test-copy", h.bridge, viewingSeatId = 1).gsm
            val copyObj = gsm.gameObjectsList.firstOrNull { it.instanceId == copyIid }
                .shouldNotBeNull()

            // Source grpId — should match Grizzly Bears, not a token grpId
            val bearsGrpId = h.bridge.cards.findGrpIdByName("Grizzly Bears")!!
            assertSoftly {
                copyObj.grpId shouldBe bearsGrpId
                copyObj.overlayGrpId shouldBe bearsGrpId
                copyObj.objectSourceGrpId shouldBe bearsGrpId
                copyObj.isCopy shouldBe true
            }
        }

        test("copy token retains cardTypes and power/toughness in GSM") {
            val h = MatchFlowHarness(seed = 42L, validating = false)
            harness = h
            h.connectAndKeepPuzzleText(puzzleText)

            val (_, copyIid) = castAndResolveCopy(h)

            val gsm = StateMapper.buildFromGame(h.game(), 1, "test-copy", h.bridge, viewingSeatId = 1).gsm
            val copyObj = gsm.gameObjectsList.first { it.instanceId == copyIid }

            assertSoftly {
                copyObj.cardTypesList.shouldNotBeEmpty()
                copyObj.cardTypesList shouldContain wotc.mtgo.gre.external.messaging.Messages.CardType.Creature
                copyObj.power.value shouldBe 2
                copyObj.toughness.value shouldBe 2
            }
        }

        test("copy token fields survive diff GSM") {
            val h = MatchFlowHarness(seed = 42L, validating = false)
            harness = h
            h.connectAndKeepPuzzleText(puzzleText)

            val (_, copyIid) = castAndResolveCopy(h)

            // First GSM — establishes baseline
            val gsm1 = StateMapper.buildFromGame(h.game(), 1, "test-copy", h.bridge, viewingSeatId = 1).gsm
            h.bridge.snapshotDiffBaseline(gsm1)

            // Trigger a state change (pass priority) so a diff is generated
            h.passPriority()

            // Second GSM — diff from baseline
            val gsm2 = StateMapper.buildDiffFromGame(h.game(), 2, "test-copy", h.bridge, viewingSeatId = 1).gsm

            // If the copy token appears in the diff, its fields must be intact
            val copyInDiff = gsm2.gameObjectsList.firstOrNull { it.instanceId == copyIid }
            if (copyInDiff != null) {
                val bearsGrpId = h.bridge.cards.findGrpIdByName("Grizzly Bears")!!
                assertSoftly {
                    copyInDiff.grpId shouldBe bearsGrpId
                    copyInDiff.isCopy shouldBe true
                    copyInDiff.cardTypesList.shouldNotBeEmpty()
                }
            }
            // If absent from diff, it means unchanged — equally valid (no field stripping)

            // Registry should have cached the grpId
            h.bridge.tokenRegistry.resolve(copyIid).shouldNotBeNull()
        }

        test("TemporaryPermanent pAnn emitted for EOT-sacrifice copy") {
            val h = MatchFlowHarness(seed = 42L, validating = false)
            harness = h
            h.connectAndKeepPuzzleText(puzzleText)

            val (copyToken, copyIid) = castAndResolveCopy(h)

            // Electroduplicate adds EndOfTurnLeavePlay SVar
            copyToken.hasSVar("EndOfTurnLeavePlay").shouldBeTrue()

            // Build GSM — should have TemporaryPermanent persistent annotation
            val gsm = StateMapper.buildFromGame(h.game(), 1, "test-copy", h.bridge, viewingSeatId = 1).gsm
            val tempPerm = gsm.persistentAnnotationsList
                .firstOrNull { ann ->
                    ann.typeList.contains(AnnotationType.TemporaryPermanent) &&
                        ann.affectorId == copyIid
                }
                .shouldNotBeNull()

            tempPerm.affectedIdsList shouldContain copyIid
            tempPerm.detailInt(DetailKeys.ABILITY_GRP_ID_UPPER) shouldBe 192424
        }

        // Test: non-EOT copy token does NOT get TemporaryPermanent pAnn
        // Deferred — requires Homunculus Horde (permanent copy, no EOT sacrifice)
        // which needs draw-count trigger infrastructure not yet built.
        // The conditional is: hasSVar("EndOfTurnLeavePlay") must be true for
        // TemporaryPermanent emission. Test 4 above proves the positive path.
    })
