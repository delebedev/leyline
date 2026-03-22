package leyline.conformance

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.ForgeCardId
import leyline.bridge.GameBootstrap
import leyline.bridge.SeatId
import leyline.game.StateMapper
import leyline.game.mapper.ActionMapper
import leyline.game.mapper.ObjectMapper
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/**
 * Treasure token grpId resolution — regression test for NPE crash.
 *
 * Crash: Treasure tokens get grpId=0 → ExposedCardRepository.findByGrpId
 * puts null into ConcurrentHashMap → NPE in ActionMapper.buildActionList.
 *
 * Fix: ActionMapper uses ObjectMapper.resolveGrpId (token-aware) instead
 * of findGrpIdByName (filters isToken=0). ExposedCardRepository guards
 * against null cache puts.
 *
 * Tests the full flow: cast Innkeeper → ETB Treasure → assert grpId →
 * Treasure mana → cast Lightning Bolt → target opponent → win.
 */
class TreasureTokenTest :
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

            // Wire up token mapping: Innkeeper → Treasure Token
            // InMemoryCardRepository uses synthetic grpIds, so we register
            // the Treasure Token and add tokenGrpIds mapping to the Innkeeper.
            val repo = TestCardRegistry.repo
            TestCardRegistry.ensureCardRegistered("Prosperous Innkeeper")
            TestCardRegistry.ensureCardRegistered("Lightning Bolt")
            TestCardRegistry.ensureCardRegistered("Centaur Courser")

            val innkeeperGrpId = repo.findGrpIdByName("Prosperous Innkeeper")!!
            val treasureGrpId = 300001
            repo.register(treasureGrpId, "Treasure Token")
            val innkeeperData = repo.findByGrpId(innkeeperGrpId)!!
            repo.registerData(
                innkeeperData.copy(tokenGrpIds = mapOf(0 to treasureGrpId)),
                "Prosperous Innkeeper",
            )
        }

        val puzzleText = """
            [metadata]
            Name:Treasure Token ETB
            Goal:Win
            Turns:3
            Difficulty:Easy
            Description:Cast Prosperous Innkeeper to create a Treasure token.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=3

            humanhand=Prosperous Innkeeper;Lightning Bolt
            humanbattlefield=Forest;Forest
            humanlibrary=Forest;Forest;Forest
            aibattlefield=Centaur Courser
            ailibrary=Mountain;Mountain;Mountain
        """.trimIndent()

        test("full treasure token flow: cast Innkeeper, ETB treasure, bolt for lethal") {
            val h = MatchFlowHarness(seed = 42L, validating = false)
            harness = h
            h.connectAndKeepPuzzleText(puzzleText)
            val human = h.bridge.getPlayer(SeatId(1))!!
            val ai = h.bridge.getPlayer(SeatId(2))!!

            // --- Preconditions ---
            human.getZone(ZoneType.Hand).cards.map { it.name } shouldContain "Prosperous Innkeeper"
            human.getZone(ZoneType.Hand).cards.map { it.name } shouldContain "Lightning Bolt"
            human.getZone(ZoneType.Battlefield).cards.count { it.name == "Forest" } shouldBe 2
            ai.life shouldBe 3

            // --- Cast Prosperous Innkeeper (1G) ---
            h.castSpellByName("Prosperous Innkeeper").shouldBeTrue()

            // Pass until Treasure Token appears on battlefield (spell + ETB trigger resolve)
            repeat(10) {
                if (human.getZone(ZoneType.Battlefield).cards.any { it.name == "Treasure Token" }) return@repeat
                h.passPriority()
            }
            human.getZone(ZoneType.Battlefield).cards.any { it.name == "Treasure Token" }.shouldBeTrue()

            // --- Assert: Innkeeper + Treasure on battlefield ---
            val bfNames = human.getZone(ZoneType.Battlefield).cards.map { it.name }
            bfNames shouldContain "Prosperous Innkeeper"
            bfNames shouldContain "Treasure Token"

            val treasure = human.getZone(ZoneType.Battlefield).cards.first { it.name == "Treasure Token" }
            treasure.isToken.shouldBeTrue()

            // --- Regression: Treasure grpId must resolve to non-zero ---
            val treasureGrpId = ObjectMapper.resolveGrpId(treasure, h.bridge.cards)
            treasureGrpId shouldBeGreaterThan 0

            // --- Regression: buildFromGame must not crash (was NPE) ---
            val gsm = StateMapper.buildFromGame(
                h.game(),
                1,
                "test-treasure",
                h.bridge,
                viewingSeatId = 1,
            ).gsm
            gsm.shouldNotBeNull()
            val treasureObj = gsm.gameObjectsList.firstOrNull { it.grpId == treasureGrpId }
            treasureObj.shouldNotBeNull()

            // --- Regression: buildActions must not crash, Treasure has ActivateMana ---
            val actions = ActionMapper.buildActions(h.game(), 1, h.bridge)
            val manaActions = actions.actionsList.filter { it.actionType == ActionType.ActivateMana }
            manaActions.size shouldBeGreaterThan 0

            val treasureInstanceId = h.bridge.getOrAllocInstanceId(ForgeCardId(treasure.id)).value
            val treasureMana = manaActions.firstOrNull { it.instanceId == treasureInstanceId }
            treasureMana.shouldNotBeNull()

            // Lightning Bolt should be castable
            val castActions = actions.actionsList.filter { it.actionType == ActionType.Cast }
            castActions.size shouldBe 1

            // --- Cast Lightning Bolt (Treasure provides R via auto-pay) ---
            h.castSpellByName("Lightning Bolt").shouldBeTrue()

            // Target opponent (seatId 2)
            h.selectTargets(listOf(2))

            // Resolve bolt → lethal
            repeat(10) {
                if (h.isGameOver()) return@repeat
                h.passPriority()
            }
            h.isGameOver().shouldBeTrue()

            // --- Assert: Sacrifice ZoneTransfer + mana-ability bracket annotations exist ---
            // Treasure sacrifice fires during bolt resolution (Forge auto-pays mana at resolution
            // time). The pre-game-over diff in sendGameOver() drains these events into a GSM.
            val allAnnotations = h.allMessages
                .filter { it.hasGameStateMessage() }
                .flatMap { it.gameStateMessage.annotationsList }

            // Sacrifice ZoneTransfer must exist (Treasure consumed for mana)
            val sacrificeTransfer = allAnnotations.filter { ann ->
                ann.typeList.any { it.name.startsWith("ZoneTransfer") } &&
                    ann.detailsList.any { d -> d.key == "category" && "Sacrifice" in d.valueStringList }
            }
            sacrificeTransfer.shouldNotBeEmpty()

            // Mana-ability bracket annotation types must be present
            // (AbilityInstanceCreated etc. also appear for Forest taps during Innkeeper cast)
            val types = allAnnotations.flatMap { it.typeList }.toSet()
            assertSoftly {
                types shouldContain AnnotationType.AbilityInstanceCreated
                types shouldContain AnnotationType.TappedUntappedPermanent
                types shouldContain AnnotationType.ManaPaid
                types shouldContain AnnotationType.AbilityInstanceDeleted
            }

            // UserActionTaken with actionType=4 (ActivateMana) must exist
            val manaActivateAnnotations = allAnnotations.filter { ann ->
                AnnotationType.UserActionTaken in ann.typeList &&
                    ann.detailsList.any { d -> d.key == "actionType" && d.getValueInt32(0) == 4 }
            }
            manaActivateAnnotations.shouldNotBeEmpty()

            // --- Assert: game over, human wins ---
            h.isGameOver().shouldBeTrue()
            human.hasWon().shouldBeTrue()
            ai.life shouldBe 0
        }
    })
