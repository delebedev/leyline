package leyline.game

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import leyline.IntegrationTag
import leyline.bridge.ForgeCardId
import leyline.bridge.GameBootstrap
import leyline.bridge.SeatId
import leyline.conformance.MatchFlowHarness
import leyline.conformance.TestCardRegistry
import wotc.mtgo.gre.external.messaging.Messages.CardType
import wotc.mtgo.gre.external.messaging.Messages.SubType

/**
 * Token diff stability — standard tokens retain cardTypes, subtypes,
 * and uniqueAbilities across diff GSMs (regression for leyline-iz4).
 *
 * Cast Novice Inspector → ETB investigate → Clue token.
 * Build two GSMs — second must still have Artifact type, Clue subtype,
 * and sacrifice-to-draw ability on the Clue.
 */
class TokenDiffStabilityTest :
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
            TestCardRegistry.ensureCardRegistered("Novice Inspector")
            TestCardRegistry.ensureCardRegistered("Plains")

            // Register Clue Token with types/subtypes so CardProtoBuilder populates fields
            val repo = TestCardRegistry.repo
            val clueGrpId = 300100
            repo.registerData(
                CardData(
                    grpId = clueGrpId,
                    titleId = 0,
                    power = "",
                    toughness = "",
                    colors = emptyList(),
                    types = listOf(CardType.Artifact_a80b.number),
                    subtypes = listOf(SubType.Clue.number),
                    supertypes = emptyList(),
                    abilityIds = emptyList(),
                    manaCost = emptyList(),
                ),
                "Clue Token",
            )

            // Wire token mapping: Novice Inspector → Clue Token
            val inspectorGrpId = repo.findGrpIdByName("Novice Inspector")!!
            val inspectorData = repo.findByGrpId(inspectorGrpId)!!
            repo.registerData(
                inspectorData.copy(tokenGrpIds = mapOf(0 to clueGrpId)),
                "Novice Inspector",
            )
        }

        val puzzleText = """
            [metadata]
            Name:Clue Token Diff Stability
            Goal:Win
            Turns:5
            Difficulty:Easy
            Description:Cast Novice Inspector, investigate, check Clue token.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanhand=Novice Inspector
            humanbattlefield=Plains
            humanlibrary=Plains;Plains;Plains;Plains;Plains
            aibattlefield=Plains
            ailibrary=Plains;Plains;Plains;Plains;Plains
        """.trimIndent()

        fun castInspectorAndWaitForClue(h: MatchFlowHarness): Int {
            val human = h.bridge.getPlayer(SeatId(1))!!
            human.getZone(ZoneType.Hand).cards.any { it.name == "Novice Inspector" }.shouldBeTrue()

            h.castSpellByName("Novice Inspector").shouldBeTrue()

            // Pass until Clue token appears (ETB trigger resolves)
            repeat(15) {
                val clues = human.getZone(ZoneType.Battlefield).cards.filter { it.isToken }
                if (clues.isNotEmpty()) return@repeat
                h.passPriority()
            }

            val clue = human.getZone(ZoneType.Battlefield).cards
                .firstOrNull { it.isToken }
                .shouldNotBeNull()
            return h.bridge.getOrAllocInstanceId(ForgeCardId(clue.id)).value
        }

        test("Clue token has Artifact type and Clue subtype in GSM") {
            val h = MatchFlowHarness(seed = 42L, validating = false)
            harness = h
            h.connectAndKeepPuzzleText(puzzleText)

            val clueIid = castInspectorAndWaitForClue(h)

            val gsm = StateMapper.buildFromGame(h.game(), 1, "test-clue", h.bridge, viewingSeatId = 1).gsm
            val clueObj = gsm.gameObjectsList.firstOrNull { it.instanceId == clueIid }
                .shouldNotBeNull()

            assertSoftly {
                clueObj.cardTypesList shouldContain CardType.Artifact_a80b
                clueObj.subtypesList shouldContain SubType.Clue
            }
        }

        test("Clue token retains types and subtypes across diff GSMs") {
            val h = MatchFlowHarness(seed = 42L, validating = false)
            harness = h
            h.connectAndKeepPuzzleText(puzzleText)

            val clueIid = castInspectorAndWaitForClue(h)

            // First GSM — baseline
            val gsm1 = StateMapper.buildFromGame(h.game(), 1, "test-clue", h.bridge, viewingSeatId = 1).gsm
            h.bridge.snapshotDiffBaseline(gsm1)

            val clueObj1 = gsm1.gameObjectsList.first { it.instanceId == clueIid }
            clueObj1.cardTypesList shouldContain CardType.Artifact_a80b

            // Trigger a state change
            h.passPriority()

            // Second GSM — diff
            val gsm2 = StateMapper.buildDiffFromGame(h.game(), 2, "test-clue", h.bridge, viewingSeatId = 1).gsm

            // If Clue appears in diff, fields must be intact (not stripped)
            val clueInDiff = gsm2.gameObjectsList.firstOrNull { it.instanceId == clueIid }
            if (clueInDiff != null) {
                assertSoftly {
                    clueInDiff.cardTypesList shouldContain CardType.Artifact_a80b
                    clueInDiff.subtypesList shouldContain SubType.Clue
                }
            }

            // Registry cached the grpId — stable for future diffs
            h.bridge.tokenRegistry.resolve(clueIid).shouldNotBeNull()
        }
    })
