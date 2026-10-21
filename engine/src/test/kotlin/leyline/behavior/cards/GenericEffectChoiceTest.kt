package leyline.behavior.cards

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.bridge.coord.acceptSettled
import leyline.testkit.SessionTest
import leyline.testkit.beInGraveyardOf
import leyline.testkit.beInHandOf
import leyline.testkit.beOnBattlefieldOf
import leyline.testkit.cancelActionReq
import leyline.testkit.gameStateMessages
import leyline.testkit.haveManaCost
import leyline.testkit.optionalActionResp
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

class GenericEffectChoiceTest :
    SessionTest({
        session(
            "Captive Audience keeps three upkeep modes despite its entry player filter",
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                aibattlefield=Captive Audience
                humanhand=Grizzly Bears
                humanlibrary=Forest;Forest;Forest;Forest;Forest
                ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
                """.trimIndent(),
            turns = 4,
        ) {
            "Captive Audience" should beOnBattlefieldOf(human)
            passUntil(maxPasses = 30) { allMessages.any { it.hasCastingTimeOptionsReq() } }.shouldBeTrue()
            val modal =
                allMessages
                    .last { it.hasCastingTimeOptionsReq() }
                    .castingTimeOptionsReq
                    .getCastingTimeOptionReq(0)
                    .modalReq
            modal.modalOptionsList.map { bridge.cardRepository.findAbilityLocalization(it.grpId)?.text } shouldBe
                listOf("Your life total becomes 4.", "Discard your hand.", "Each opponent creates five 2/2 black Zombie creature tokens.")
            respondModalChoice(listOf(modal.getModalOptions(1).grpId))
            passUntil(maxPasses = 10) { human.getCardsIn(ZoneType.Graveyard).any { it.name == "Grizzly Bears" } }.shouldBeTrue()
            passUntil(maxPasses = 10) { human.getCardsIn(ZoneType.Hand).any { it.name == "Forest" } }.shouldBeTrue()
            val landPlayed = playLand("Forest")
            assertSoftly {
                landPlayed.shouldBeTrue()
                "Grizzly Bears" should beInGraveyardOf(human)
                human.life shouldBe 20
                "Forest" should beOnBattlefieldOf(human)
            }
        }
        session(
            "granted affinity offers and payment survive a catalog-owned draw replacement",
            forgeCatalog = true,
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanbattlefield=Abundance;Mycosynth Golem;Ornithopter;Island;Island;Island
                humanhand=Reach Through Mists;Juggernaut
                humanlibrary=Grizzly Bears;Forest;Mountain
                ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
                """.trimIndent(),
            turns = 3,
        ) {
            val handId = human.hand.iid("Juggernaut")
            val before =
                allMessages.last { it.hasActionsAvailableReq() }.actionsAvailableReq.actionsList.single {
                    it.actionType == ActionType.Cast && it.instanceId == handId
                }
            before should haveManaCost(generic = 2)
            holdNextOptionalAction()
            val drawCast = castSpellByName("Reach Through Mists")
            val offered = passUntil(maxPasses = 6) { allMessages.any { it.hasOptionalActionMessage() } }
            assertSoftly {
                drawCast.shouldBeTrue()
                offered.shouldBeTrue()
                allMessages.none { it.hasCastingTimeOptionsReq() } shouldBe true
            }
            respondToOptionalAction(accept = true)
            passUntil(maxPasses = 6) { allMessages.any { it.hasCastingTimeOptionsReq() } }.shouldBeTrue()
            val modal =
                allMessages
                    .last { it.hasCastingTimeOptionsReq() }
                    .castingTimeOptionsReq
                    .getCastingTimeOptionReq(0)
                    .modalReq
            modal.modalOptionsList.map { bridge.cardRepository.findAbilityLocalization(it.grpId)?.text } shouldBe
                listOf("Land", "Nonland")
            respondModalChoice(listOf(modal.getModalOptions(0).grpId))
            passUntilResolved()
            val after =
                allMessages.last { it.hasActionsAvailableReq() }.actionsAvailableReq.actionsList.single {
                    it.actionType == ActionType.Cast && it.instanceId == handId
                }
            assertSoftly {
                after should haveManaCost(generic = 2)
                "Forest" should beInHandOf(human)
                human.getCardsIn(ZoneType.Library).map { it.name } shouldBe listOf("Mountain", "Grizzly Bears")
            }
            val start = messageSnapshot()
            val castAccepted = castSpellByName("Juggernaut")
            passUntilResolved()
            val spellId = human.battlefield.iid("Juggernaut")
            val paid =
                messagesSince(start).gameStateMessages().flatMap { it.annotationsList }.filter {
                    AnnotationType.ManaPaid in it.typeList && spellId in it.affectedIdsList
                }
            val continued = playLand("Forest")
            assertSoftly {
                castAccepted.shouldBeTrue()
                paid.size shouldBe 2
                paid.map { it.affectorId }.distinct().size shouldBe 2
                human.getCardsIn(ZoneType.Battlefield).count { it.name == "Island" && it.isTapped } shouldBe 3
                "Juggernaut" should beOnBattlefieldOf(human)
                continued.shouldBeTrue()
                "Forest" should beOnBattlefieldOf(human)
                game().stack.isEmpty shouldBe true
            }
        }
        session(
            "Abundance decline draws the ordinary top card without offering replacement modes",
            forgeCatalog = true,
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanbattlefield=Abundance;Island;Forest;Forest
                humanhand=Reach Through Mists
                humanlibrary=Forest;Grizzly Bears;Mountain
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
            turns = 3,
        ) {
            val drawnBefore = human.numDrawnThisTurn
            holdNextOptionalAction()
            val cast = castSpellByName("Reach Through Mists")
            val offered =
                passUntil(maxPasses = 6) {
                    allMessages.any { it.hasOptionalActionMessage() || it.hasCastingTimeOptionsReq() }
                }
            assertSoftly {
                cast.shouldBeTrue()
                offered.shouldBeTrue()
                allMessages.count { it.hasOptionalActionMessage() } shouldBe 1
                allMessages.none { it.hasCastingTimeOptionsReq() } shouldBe true
                human.getCardsIn(ZoneType.Library).map { it.name } shouldBe listOf("Forest", "Grizzly Bears", "Mountain")
                human.getCardsIn(ZoneType.Hand).shouldBeEmpty()
                bridge.hasPendingNonActionInteraction().shouldBeTrue()
            }
            val consent = allMessages.single { it.hasOptionalActionMessage() }
            consent.optionalActionMessage.sourceId shouldBe human.battlefield.iid("Abundance")
            respondToOptionalAction(accept = false)
            passUntilResolved()
            assertSoftly {
                "Forest" should beInHandOf(human)
                human.numDrawnThisTurn shouldBe drawnBefore + 1
                human.getCardsIn(ZoneType.Library).map { it.name } shouldBe listOf("Grizzly Bears", "Mountain")
                allMessages.none { it.hasCastingTimeOptionsReq() } shouldBe true
                bridge.hasPendingNonActionInteraction() shouldBe false
            }
            val continued = playLand("Forest")
            assertSoftly {
                continued.shouldBeTrue()
                "Forest" should beOnBattlefieldOf(human, 3)
            }
        }
        session(
            "an opponent-controlled draw replacement asks the affected player and binds the replacement source",
            forgeCatalog = true,
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanbattlefield=Island;Forest;Forest
                aibattlefield=Abundance
                humanhand=Reach Through Mists
                humanlibrary=Forest;Grizzly Bears;Mountain
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
            turns = 3,
        ) {
            val replacement =
                ai.battlefield
                    .card("Abundance")
                    .replacementEffects
                    .single()
            replacement.putParam("ValidPlayer", "Opponent")
            holdNextOptionalAction()
            castSpellByName("Reach Through Mists").shouldBeTrue()
            passUntil(maxPasses = 6) { allMessages.any { it.hasOptionalActionMessage() } }.shouldBeTrue()
            val consent = allMessages.single { it.hasOptionalActionMessage() }
            val sourceId = ai.battlefield.iid("Abundance")
            assertSoftly {
                consent.optionalActionMessage.sourceId shouldBe sourceId
                consent.prompt.parametersList
                    .single { it.parameterName == "CardId" }
                    .numberValue shouldBe sourceId
                consent.allowCancel shouldBe AllowCancel.No_a526
                allMessages.takeWhile { it != consent }.gameStateMessages().any { gsm ->
                    gsm.gameObjectsList.any { it.instanceId == sourceId && it.grpId > 0 }
                } shouldBe true
                human.getCardsIn(ZoneType.Library).map { it.name } shouldBe listOf("Forest", "Grizzly Bears", "Mountain")
                allMessages.none { it.hasCastingTimeOptionsReq() } shouldBe true
            }
            respondToOptionalAction(accept = false)
            passUntilResolved()
            assertSoftly {
                "Forest" should beInHandOf(human)
                ai.getCardsIn(ZoneType.Hand).shouldBeEmpty()
                allMessages.none { it.hasCastingTimeOptionsReq() } shouldBe true
            }
            playLand("Forest").shouldBeTrue()
        }
        session(
            "a consumed replacement answer cannot answer the next draw's consent",
            forgeCatalog = true,
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanbattlefield=Abundance;Island;Island;Forest
                humanhand=Reach Through Mists;Reach Through Mists
                humanlibrary=Forest;Mountain;Grizzly Bears
                ailibrary=Mountain;Mountain;Mountain
                """.trimIndent(),
            turns = 3,
        ) {
            holdNextOptionalAction()
            castSpellByName("Reach Through Mists").shouldBeTrue()
            passUntil(maxPasses = 6) { allMessages.any { it.hasOptionalActionMessage() } }.shouldBeTrue()
            val first = allMessages.single { it.hasOptionalActionMessage() }
            respondToOptionalAction(accept = false)
            passUntilResolved()
            holdNextOptionalAction()
            castSpellByName("Reach Through Mists").shouldBeTrue()
            passUntil(maxPasses = 6) { allMessages.count { it.hasOptionalActionMessage() } == 2 }.shouldBeTrue()
            val pending = bridge.cutCoordinator.currentBlockingInteraction()
            val current = allMessages.last { it.hasOptionalActionMessage() }
            send(
                optionalActionResp(accept = true)
                    .toBuilder()
                    .setGameStateId(first.gameStateId)
                    .setRespId(first.msgId)
                    .build(),
            )
            drainSink()
            send(
                optionalActionResp(accept = true)
                    .toBuilder()
                    .setGameStateId(current.gameStateId)
                    .setRespId(first.msgId)
                    .build(),
            )
            drainSink()
            send(
                cancelActionReq()
                    .toBuilder()
                    .setGameStateId(current.gameStateId)
                    .setRespId(current.msgId)
                    .build(),
            )
            drainSink()
            assertSoftly {
                bridge.cutCoordinator.currentBlockingInteraction() shouldBe pending
                allMessages.none { it.hasCastingTimeOptionsReq() } shouldBe true
                human.getCardsIn(ZoneType.Library).map { it.name } shouldBe listOf("Mountain", "Grizzly Bears")
                "Forest" should beInHandOf(human)
            }
            respondToOptionalAction(accept = false)
            passUntilResolved()
            assertSoftly {
                human.getCardsIn(ZoneType.Hand).map { it.name }.sorted() shouldBe listOf("Forest", "Mountain")
                human.getCardsIn(ZoneType.Library).map { it.name } shouldBe listOf("Grizzly Bears")
                allMessages.none { it.hasCastingTimeOptionsReq() } shouldBe true
                bridge.hasPendingNonActionInteraction() shouldBe false
            }
            playLand("Forest").shouldBeTrue()
        }
        for ((index, scenario) in listOf(
            Triple("Land", "Grizzly Bears;Forest;Mountain", "Forest"),
            Triple("Nonland", "Forest;Grizzly Bears;Mountain", "Grizzly Bears"),
        ).withIndex()) {
            val (kind, library, selected) = scenario
            session(
                "Abundance replaces a draw with the selected $kind card and continues play",
                puzzle =
                    """
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=20
                    humanbattlefield=Abundance;Island;Forest;Forest
                    humanhand=Reach Through Mists
                    humanlibrary=$library
                    ailibrary=Mountain;Mountain;Mountain
                    """.trimIndent(),
                turns = 3,
            ) {
                val drawnBefore = human.numDrawnThisTurn
                holdNextOptionalAction()
                val cast = castSpellByName("Reach Through Mists")
                val offered =
                    passUntil(maxPasses = 6) {
                        allMessages.any { it.hasOptionalActionMessage() || it.hasCastingTimeOptionsReq() }
                    }
                assertSoftly {
                    cast.shouldBeTrue()
                    offered.shouldBeTrue()
                    allMessages.count { it.hasOptionalActionMessage() } shouldBe 1
                    allMessages.none { it.hasCastingTimeOptionsReq() } shouldBe true
                    human.getCardsIn(ZoneType.Library).map { it.name } shouldBe library.split(';')
                    human.getCardsIn(ZoneType.Hand).shouldBeEmpty()
                }
                val consent = allMessages.single { it.hasOptionalActionMessage() }
                consent.optionalActionMessage.sourceId shouldBe human.battlefield.iid("Abundance")
                respondToOptionalAction(accept = true)
                passUntil(maxPasses = 6) { allMessages.any { it.hasCastingTimeOptionsReq() } }.shouldBeTrue()
                val modal =
                    allMessages
                        .last { it.hasCastingTimeOptionsReq() }
                        .castingTimeOptionsReq
                        .getCastingTimeOptionReq(0)
                        .modalReq
                modal.modalOptionsList.map { bridge.cardRepository.findAbilityLocalization(it.grpId)?.text } shouldBe
                    listOf("Land", "Nonland")
                respondModalChoice(listOf(modal.getModalOptions(index).grpId))
                passUntilResolved()
                assertSoftly {
                    selected should beInHandOf(human)
                    human.numDrawnThisTurn shouldBe drawnBefore
                    human.getCardsIn(ZoneType.Library).map { it.name } shouldBe
                        listOf("Mountain", if (kind == "Land") "Grizzly Bears" else "Forest")
                    (human.getCardsIn(ZoneType.Hand) + human.getCardsIn(ZoneType.Library)).map { it.name }.sorted() shouldBe
                        library.split(';').sorted()
                }
                if (kind == "Land") {
                    playLand(selected).shouldBeTrue()
                } else {
                    castSpellByName(selected).shouldBeTrue()
                    passUntilResolved()
                }
                selected should beOnBattlefieldOf(human, if (kind == "Land") 3 else 1)
            }
        }
        for ((index, token) in listOf("Food", "Treasure").withIndex()) {
            session(
                "Tireless Provisioner landfall offers both tokens and creates selected $token",
                puzzle =
                    """
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=20
                    humanbattlefield=Tireless Provisioner
                    humanhand=Forest
                    humanlibrary=Forest;Forest;Forest;Forest;Forest
                    ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
                    """.trimIndent(),
                turns = 3,
            ) {
                playLand("Forest").shouldBeTrue()
                passUntil(maxPasses = 3) {
                    allMessages.any { it.hasCastingTimeOptionsReq() }
                }.shouldBeTrue()
                val choiceMessage = allMessages.last { it.hasCastingTimeOptionsReq() }
                val modal =
                    choiceMessage.castingTimeOptionsReq
                        .getCastingTimeOptionReq(0)
                        .modalReq
                assertSoftly {
                    choiceMessage.allowCancel shouldBe AllowCancel.No_a526
                    choiceMessage.allowUndo shouldBe false
                    bridge.cutCoordinator.acceptSettled(cancelActionReq(), choiceMessage.gameStateId) shouldBe false
                    bridge.hasPendingNonActionInteraction().shouldBeTrue()
                    modal.minSel shouldBe 1
                    modal.maxSel shouldBe 1
                    modal.modalOptionsList.map { bridge.cardRepository.findAbilityLocalization(it.grpId)?.text } shouldBe
                        listOf("Food", "Treasure")
                }
                respondModalChoice(listOf(modal.getModalOptions(index).grpId))
                passUntilResolved()
                "$token Token" should beOnBattlefieldOf(human)
                "Tireless Provisioner" should beOnBattlefieldOf(human)
            }
        }
    })
