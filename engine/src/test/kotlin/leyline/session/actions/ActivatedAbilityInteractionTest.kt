package leyline.session.actions

import forge.card.mana.ManaAtom
import forge.game.card.CounterEnumType
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.PlayerAction
import leyline.game.mapping.ActionMapper
import leyline.game.mapping.ZoneIds
import leyline.game.snapshot.GsmSnapshot
import leyline.testkit.FixturePinned
import leyline.testkit.MatchFlowHarness
import leyline.testkit.SessionTest
import leyline.testkit.after
import leyline.testkit.deletedPersistentAnnotationIds
import leyline.testkit.detailInt
import leyline.testkit.gameStateMessages
import leyline.testkit.persistentAnnotationsOfType
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.AutoPassOption
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import wotc.mtgo.gre.external.messaging.Messages.SettingsMessage

/**
 * Session-tier activated ability tests — full MatchSession round-trip.
 *
 * Board-level action field tests live in [ActivatedAbilityTest] (BoardTest).
 */
@FixturePinned
class ActivatedAbilityInteractionTest :
    SessionTest({
        for ((label, description) in listOf(
            "plus" to "You gain 3 life and draw a card.",
            "zero" to "Add {C}{C}{C}.",
            "ultimate" to "Search your library for any number of colorless nonland cards",
        )) {
            session(
                "generated loyalty identity selects the $label effect",
                forgeCatalog = true,
                fullControl = true,
                puzzle = """
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=40
                    AILife=40
                    humanbattlefield=Ugin, Eye of the Storms|Counters:LOYALTY=11
                    humanhand=Ornithopter
                    humanlibrary=Ornithopter;Plains;Plains;Plains
                    ailibrary=Mountain;Mountain;Mountain
                """,
            ) {
                val ugin = human.battlefield.card("Ugin, Eye of the Storms")
                val repo = bridge.cardRepository
                val data = requireNotNull(repo.findByGrpId(requireNotNull(repo.findGrpIdByName(ugin.name))))
                val expectedId =
                    data.abilityIds
                        .single {
                            repo
                                .findAbilityLocalization(
                                    it.first,
                                )?.text
                                ?.startsWith(description) == true
                        }.first
                val projection = ActionMapper.buildProjectionFromSnapshot(1, GsmSnapshot.capture(game(), bridge, "test", 0), bridge)
                val offers =
                    projection.offers.filter {
                        it.action.instanceId == bridge.instanceId(ugin) &&
                            it.action.actionType == ActionType.Activate_add3
                    }
                val offer = offers.single { it.action.abilityGrpId == expectedId }
                val ability = requireNotNull((offer.command as PlayerAction.ActivateAbility).ability)
                assertSoftly {
                    offers.size shouldBe 3
                    ability.getParam("SpellDescription").startsWith(description) shouldBe true
                    offer.action.uniqueAbilityId shouldBe 50 + data.abilityIds.indexOfFirst { it.first == expectedId }
                }
                submitAction(offer.action)
                if (label == "ultimate") {
                    passUntil(maxPasses = 5) { allMessages.any { it.hasSearchReq() } }.shouldBeTrue()
                    respondToSearch(emptyList())
                    passUntil(maxPasses = 5) { game().stack.isEmpty }.shouldBeTrue()
                    human.graveyard.card(ugin.name)
                } else {
                    assertSoftly {
                        passUntil(maxPasses = 5) { game().stack.isEmpty }.shouldBeTrue()
                        human.life shouldBe if (label == "plus") 43 else 40
                        ugin.getCounters(CounterEnumType.LOYALTY) shouldBe if (label == "plus") 13 else 11
                        if (label == "zero") human.manaPool.count { it.color == ManaAtom.COLORLESS.toByte() } shouldBe 3
                        castSpellByName("Ornithopter").shouldBeTrue()
                        passUntil(maxPasses = 5) { human.isCardInPlay("Ornithopter") }.shouldBeTrue()
                    }
                }
            }
        }

        fun MatchFlowHarness.enableStackAutoResolve() {
            updateSettings(
                SettingsMessage
                    .newBuilder()
                    .setAutoPassOption(AutoPassOption.ResolveMyStackEffects)
                    .build(),
            )
        }

        session(
            "Goblin Fireslinger tap-to-ping deals damage to opponent",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=5

                humanbattlefield=Goblin Fireslinger
                humanlibrary=Mountain
                aibattlefield=Centaur Courser
                ailibrary=Mountain
                """,
        ) {
            assertSoftly {
                phase() shouldBe "MAIN1"
                ai.life shouldBe 5
            }

            // Activate tap ability → wait for SelectTargetsReq before responding
            // (drainSink returns before the engine emits the prompt under load).
            assertSoftly {
                activateAbility("Goblin Fireslinger").shouldBeTrue()
                passUntil(maxPasses = 5) { allMessages.any { it.hasSelectTargetsReq() } }.shouldBeTrue()
            }
            selectTargets(listOf(OPPONENT_SEAT))

            val targetSpec = allMessages.persistentAnnotationsOfType(AnnotationType.TargetSpec).single()
            val stackAbilityIids =
                allMessages
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.gameObjectsList }
                    .filter { it.type == GameObjectType.Ability }
                    .map { it.instanceId }
            assertSoftly {
                stackAbilityIids shouldContain targetSpec.affectorId
                targetSpec.affectedIdsList shouldBe listOf(OPPONENT_SEAT)
                allMessages.persistentAnnotationsOfType(AnnotationType.LinkInfo).none {
                    it.affectorId in stackAbilityIids
                } shouldBe true
                passUntil(maxPasses = 10) { ai.life < 5 }.shouldBeTrue()
                ai.life shouldBe 4
                allMessages.deletedPersistentAnnotationIds() shouldContain targetSpec.id
            }
        }

        session(
            "modal activated sacrifice ability asks mode before target and costs",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Goblin Cratermaker;Mountain;Mountain
                humanlibrary=Mountain
                aibattlefield=Centaur Courser
                ailibrary=Mountain
                """,
        ) {
            val slice = after { activateAbility("Goblin Cratermaker").shouldBeTrue() }

            slice.expectNoPayCostsReq()
            slice.expectNoSelectTargetsReq()
            val modalReq =
                slice
                    .expectOneCastingTimeOptionsReq()
                    .castingTimeOptionReqList
                    .single()
                    .modalReq
            modalReq.modalOptionsList.map { it.grpId } shouldBe listOf(121501)
            modalReq.excludedOptionsList.map { it.grpId } shouldBe listOf(121502)
        }

        session(
            "activated Discover links its stack ability to the sacrificed source",
            puzzleFile = "data/puzzles/discover-hidden-courtyard.pzl",
        ) {
            val sourceIid = human.battlefield.iid("Hidden Courtyard")

            enableStackAutoResolve()
            activateAbility("Hidden Courtyard").shouldBeTrue()
            passUntil(maxPasses = 5) {
                allMessages.persistentAnnotationsOfType(AnnotationType.LinkInfo).isNotEmpty()
            }.shouldBeTrue()

            val abilityObjects =
                allMessages
                    .gameStateMessages()
                    .flatMap { it.gameObjectsList }
                    .filter { it.type == GameObjectType.Ability }
                    .distinctBy { it.instanceId }
            val abilityObject =
                abilityObjects.firstOrNull { it.grpId == 169776 }
                    ?: error(
                        "No activated Discover Ability object; projected=" +
                            abilityObjects.map { "iid=${it.instanceId} grp=${it.grpId} source=${it.objectSourceGrpId}" },
                    )
            val linkInfo = allMessages.persistentAnnotationsOfType(AnnotationType.LinkInfo).single()
            assertSoftly {
                abilityObject.objectSourceGrpId shouldBe 87440
                linkInfo.affectorId shouldBe abilityObject.instanceId
                linkInfo.affectedIdsList shouldBe listOf(sourceIid)
                linkInfo.detailInt("LinkType") shouldBe 2
            }

            respondToOptionalAction(accept = true)
            passUntil(maxPasses = 10) {
                human.getZone(ZoneType.Battlefield).cards.any { it.name == "Llanowar Elves" } &&
                    human.getZone(ZoneType.Graveyard).cards.any { it.name == "Hidden Courtyard" }
            }.shouldBeTrue()
            allMessages.deletedPersistentAnnotationIds() shouldContain linkInfo.id
            human.battlefield.card("Llanowar Elves")
            human.graveyard.card("Hidden Courtyard")
            allMessages.persistentAnnotationsOfType(AnnotationType.TriggeringObject).none {
                it.affectorId == abilityObject.instanceId
            } shouldBe true
            abilityObject.zoneId shouldBe ZoneIds.STACK
        }
    })
