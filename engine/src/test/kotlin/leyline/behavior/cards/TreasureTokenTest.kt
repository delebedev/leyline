package leyline.behavior.cards

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.coord.resolveActionOffer
import leyline.bridge.handoff.ActionResponseKey
import leyline.game.mapping.ActionMapper
import leyline.game.snapshot.GrpIdResolver
import leyline.game.snapshot.GsmSnapshot
import leyline.testkit.*
import leyline.testkit.SessionTest
import leyline.testkit.TestCardRegistry
import leyline.testkit.allAnnotations
import leyline.testkit.beInHandOf
import leyline.testkit.beOnBattlefieldOf
import leyline.testkit.gsm
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import leyline.testkit.StateMapperShell as StateMapper

/**
 * Treasure token grpId resolution — regression test for NPE crash.
 *
 * Crash: Treasure tokens get grpId=0 → SqliteCardRepository.findByGrpId
 * puts null into ConcurrentHashMap → NPE in ActionMapper's action builders.
 *
 * Fix: ActionMapper uses GrpIdResolver.resolve (token-aware) instead
 * of findGrpIdByName (filters isToken=0). SqliteCardRepository guards
 * against null cache puts.
 *
 * Tests the full flow: cast Innkeeper → ETB Treasure → assert grpId →
 * Treasure mana → cast Lightning Bolt → target opponent → win.
 */
class TreasureTokenTest :
    SessionTest({

        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
            TestCardRegistry.ensureCardRegistered("Prosperous Innkeeper")
            TestCardRegistry.ensureCardRegistered("Lightning Bolt")
            TestCardRegistry.ensureCardRegistered("Centaur Courser")
        }

        val puzzleText =
            """
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

        for (grantedChoice in 0..1) {
            session(
                "each repeated Treasure grant executes its selected ability $grantedChoice",
                puzzleFile = "data/puzzles/granted-treasure-mana.pzl",
                fullControl = true,
            ) {
                castSpellByName("Strike It Rich").shouldBeTrue()
                passUntil { human.getZone(ZoneType.Battlefield).cards.any { it.name == "Treasure Token" } }.shouldBeTrue()
                val treasure = human.battlefield.card("Treasure Token")
                val projection = ActionMapper.buildProjectionFromSnapshot(1, GsmSnapshot.capture(game(), bridge, "test", 0), bridge)
                val grants =
                    projection.offers.filter {
                        val command = it.command as? leyline.bridge.handoff.PlayerAction.ActivateMana
                        command?.ability?.grantorStatic != null
                    }
                val projected =
                    allMessages
                        .flatMap { it.gameStateMessage.gameObjectsList }
                        .last { it.instanceId == human.battlefield.iid(treasure) }
                assertSoftly {
                    grants.size shouldBe 2
                    grants.map { it.action.abilityGrpId }.distinct().size shouldBe 1
                    grants.first().action.abilityGrpId shouldBeGreaterThan 0
                    grants.map { it.action.uniqueAbilityId }.distinct().size shouldBe 2
                    grants.forEach { offer ->
                        projected.uniqueAbilitiesList
                            .any { it.id == offer.action.uniqueAbilityId && it.grpId == offer.action.abilityGrpId }
                            .shouldBeTrue()
                    }
                    leyline.bridge.coord.hasAmbiguousActionCatalog(projection.offers) shouldBe false
                }
                val selected = grants[grantedChoice].action
                val response = selected.toBuilder()
                response.getManaPaymentOptionsBuilder(0).getManaBuilder(0).color =
                    wotc.mtgo.gre.external.messaging.Messages.ManaColor.Red_afc9
                val catalog =
                    grants
                        .mapIndexed { index, offer -> index.toLong() to offer }
                        .groupBy { ActionResponseKey.from(it.second.action) }
                resolveActionOffer(catalog, response.build())?.second shouldBe grants[grantedChoice]
                resolveActionOffer(catalog, response.clone().clearUniqueAbilityId().build()) shouldBe null
                submitAction(response.build())
                assertSoftly {
                    human.manaPool.totalMana() shouldBe 2
                    human
                        .getZone(ZoneType.Battlefield)
                        .cards
                        .none { it.name == "Treasure Token" }
                        .shouldBeTrue()
                }
                castSpellByName("Lightning Strike").shouldBeTrue()
                selectTargets(listOf(OPPONENT_SEAT))
                passUntil { isGameOver() }.shouldBeTrue()
                human.hasWon().shouldBeTrue()
            }
        }

        session("full treasure token flow: cast Innkeeper, ETB treasure, bolt for lethal", puzzle = puzzleText) {
            // --- Preconditions ---
            assertSoftly {
                "Prosperous Innkeeper" should beInHandOf(human)
                "Lightning Bolt" should beInHandOf(human)
                "Forest" should beOnBattlefieldOf(human, count = 2)
                ai.life shouldBe 3
            }

            // --- Cast Prosperous Innkeeper (1G) ---
            castSpellByName("Prosperous Innkeeper").shouldBeTrue()

            // Pass until Treasure Token appears on battlefield (spell + ETB trigger resolve)
            repeat(10) {
                if (human.getZone(ZoneType.Battlefield).cards.any { it.name == "Treasure Token" }) return@repeat
                passPriority()
            }
            human
                .getZone(ZoneType.Battlefield)
                .cards
                .any { it.name == "Treasure Token" }
                .shouldBeTrue()

            // --- Assert: Innkeeper + Treasure on battlefield ---
            val bfNames = human.getZone(ZoneType.Battlefield).cards.map { it.name }
            bfNames shouldContain "Prosperous Innkeeper"
            bfNames shouldContain "Treasure Token"

            val treasure = human.battlefield.card("Treasure Token")
            treasure.isToken.shouldBeTrue()

            // --- Regression: Treasure grpId must resolve to non-zero ---
            val treasureGrpId = GrpIdResolver.resolve(treasure, bridge.cardRepository)
            treasureGrpId shouldBeGreaterThan 0

            // --- Regression: buildFromSnapshot must not crash (was NPE) ---
            val snapTreasure = GsmSnapshot.capture(game(), bridge, "test-treasure", 1)
            val gsm =
                StateMapper
                    .buildFromSnapshot(
                        snapTreasure,
                        1,
                        "test-treasure",
                        bridge,
                        viewingSeatId = 1,
                        effectFacts = bridge.materializeEffectProjectionFacts(),
                        abilityExhaustionFacts = leyline.game.state.AbilityExhaustionFacts(),
                    ).gsm
            gsm.shouldNotBeNull()
            val treasureObj = gsm.gameObjectsList.firstOrNull { it.grpId == treasureGrpId }
            treasureObj.shouldNotBeNull()

            // --- Regression: buildActions must not crash, Treasure has ActivateMana ---
            val actions = ActionMapper.buildFromSnapshot(1, GsmSnapshot.capture(game(), bridge, "test", 0), bridge)
            val manaActions = actions.actionsList.filter { it.actionType == ActionType.ActivateMana }
            manaActions.size shouldBeGreaterThan 0

            val treasureInstanceId = human.battlefield.iid(treasure)
            val treasureMana = manaActions.firstOrNull { it.instanceId == treasureInstanceId }
            treasureMana.shouldNotBeNull()

            // Lightning Bolt should be castable
            val castActions = actions.actionsList.filter { it.actionType == ActionType.Cast }
            castActions.size shouldBe 1

            // --- Cast Lightning Bolt (Treasure provides R via auto-pay) ---
            castSpellByName("Lightning Bolt").shouldBeTrue()

            // Target opponent (seatId 2)
            selectTargets(listOf(OPPONENT_SEAT))

            // Resolve bolt → lethal
            repeat(10) {
                if (isGameOver()) return@repeat
                passPriority()
            }
            isGameOver().shouldBeTrue()

            // --- Assert: Sacrifice ZoneTransfer + mana-ability bracket annotations exist ---
            // Treasure sacrifice fires during bolt resolution (Forge auto-pays mana at resolution
            // time). The engine-owned terminal cut drains these events into a GSM before game-over output.
            val allAnnotations =
                allMessages
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.annotationsList }

            // Sacrifice ZoneTransfer must exist (Treasure consumed for mana)
            val sacrificeTransfer =
                allAnnotations.filter { ann ->
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
            val manaActivateAnnotations =
                allAnnotations.filter { ann ->
                    AnnotationType.UserActionTaken in ann.typeList &&
                        ann.detailsList.any { d -> d.key == "actionType" && d.getValueInt32(0) == 4 }
                }
            manaActivateAnnotations.shouldNotBeEmpty()

            // --- Assert: game over, human wins ---
            assertSoftly {
                isGameOver().shouldBeTrue()
                human.hasWon().shouldBeTrue()
                ai.life shouldBe 0
            }
        }
    })
