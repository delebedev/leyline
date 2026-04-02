package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.ManaColor

/**
 * Dual land ColorProduction annotation emits Arena ManaColor ordinals.
 *
 * Real server: Gruul Guildgate → ColorProduction colors=[4, 5] (Red, Green).
 * Bug: leyline emitted Forge bitmask values instead of Arena ordinals.
 */
class DualLandColorProductionTest :
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null
        afterEach {
            harness?.shutdown()
            harness = null
        }

        test("basic Forest ColorProduction emits ordinal [5]") {
            val pzl = """
            [metadata]
            Name:Basic Land Ordinal
            Goal:Win
            Turns:10
            Difficulty:Easy
            Description:Play a Forest and verify ColorProduction ordinal.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanhand=Forest
            humanlibrary=Forest;Forest;Forest;Forest;Forest
            aibattlefield=Centaur Courser
            ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
            """.trimIndent()

            val h = MatchFlowHarness(seed = 42L, validating = false)
            harness = h
            h.connectAndKeepPuzzleText(pzl)

            h.phase() shouldBe "MAIN1"
            h.playLand()

            val colorProd = h.allMessages
                .filter { it.hasGameStateMessage() }
                .flatMap { it.gameStateMessage.persistentAnnotationsList }
                .firstOrNull { AnnotationType.ColorProduction in it.typeList }
            colorProd.shouldNotBeNull()

            val colors = colorProd.detailsList.first { it.key == "colors" }
            val ordinals = (0 until colors.valueInt32Count).map { colors.getValueInt32(it) }
            ordinals shouldBe listOf(ManaColor.Green_afc9.number) // [5]
        }

        test("Jungle Hollow ColorProduction emits ordinals [3, 5] not bitmasks") {
            val pzl = """
            [metadata]
            Name:Dual Land Ordinals
            Goal:Win
            Turns:10
            Difficulty:Easy
            Description:Play a dual land and verify ColorProduction ordinals.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanhand=Jungle Hollow
            humanlibrary=Forest;Forest;Forest;Forest;Forest
            aibattlefield=Centaur Courser
            ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
            """.trimIndent()

            val h = MatchFlowHarness(seed = 42L, validating = false)
            harness = h
            h.connectAndKeepPuzzleText(pzl)

            h.phase() shouldBe "MAIN1"
            h.playLand()

            val colorProd = h.allMessages
                .filter { it.hasGameStateMessage() }
                .flatMap { it.gameStateMessage.persistentAnnotationsList }
                .firstOrNull { AnnotationType.ColorProduction in it.typeList }

            colorProd.shouldNotBeNull()

            val colors = colorProd.detailsList.first { it.key == "colors" }
            val ordinals = (0 until colors.valueInt32Count).map { colors.getValueInt32(it) }
            // Jungle Hollow = B + G → Arena ordinals Black=3, Green=5
            ordinals.shouldContainExactlyInAnyOrder(
                ManaColor.Black_afc9.number,
                ManaColor.Green_afc9.number,
            )
        }
    })
