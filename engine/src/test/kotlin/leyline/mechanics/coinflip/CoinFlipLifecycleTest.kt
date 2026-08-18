package leyline.mechanics.coinflip

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.game.codes.DetailKeys
import leyline.game.mapping.PromptIds
import leyline.testkit.SessionTest
import leyline.testkit.annotation
import leyline.testkit.detailInt
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.ReferenceType

class CoinFlipLifecycleTest :
    SessionTest({
        fun GameStateMessage.annotationTypes(): List<AnnotationType> = annotationsList.flatMap { it.typeList }

        session(
            "Tavern Swindler emits coin flip annotation and prompt during resolution",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Tavern Swindler
                humanlibrary=Swamp;Swamp;Swamp
                ailibrary=Mountain;Mountain;Mountain
                """,
        ) {
            val before = messageSnapshot()
            activateAbility("Tavern Swindler").shouldBe(true)
            passUntil(maxPasses = 10) {
                messagesSince(before).any { it.type == GREMessageType.PromptReq && it.prompt.promptId == PromptIds.COIN_FLIP }
            }.shouldBe(true)

            val messages = messagesSince(before)
            val resolutionGsm =
                messages
                    .firstOrNull { message ->
                        message.hasGameStateMessage() &&
                            message.gameStateMessage.annotationsList.any { AnnotationType.CoinFlip in it.typeList }
                    }?.gameStateMessage
                    .shouldNotBeNull()
            val prompt = messages.first { it.type == GREMessageType.PromptReq && it.prompt.promptId == PromptIds.COIN_FLIP }.prompt
            val annotations = resolutionGsm.annotationsList
            val resolutionStart = annotations.annotation(AnnotationType.ResolutionStart)
            val coinFlip = annotations.annotation(AnnotationType.CoinFlip)
            val resolutionComplete = annotations.annotation(AnnotationType.ResolutionComplete)
            val abilityDeleted = annotations.annotation(AnnotationType.AbilityInstanceDeleted)
            val types = annotations.map { it.typeList.first() }
            val lifeDeltas =
                messages
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.annotationsList }
                    .filter { AnnotationType.ModifiedLife in it.typeList }
                    .map { it.detailInt(DetailKeys.LIFE) }

            withClue("annotations=${resolutionGsm.annotationTypes()}") {
                assertSoftly {
                    types.indexOf(AnnotationType.ResolutionStart) shouldBeLessThan types.indexOf(AnnotationType.CoinFlip)
                    types.indexOf(AnnotationType.CoinFlip) shouldBeLessThan types.indexOf(AnnotationType.ResolutionComplete)
                    types.indexOf(AnnotationType.ResolutionComplete) shouldBeLessThan types.indexOf(AnnotationType.AbilityInstanceDeleted)
                    coinFlip.affectorId shouldBe resolutionStart.affectorId
                    resolutionComplete.affectorId shouldBe resolutionStart.affectorId
                    abilityDeleted.affectedIdsList shouldContain resolutionStart.affectorId
                    coinFlip.affectedIdsList shouldBe listOf(1)
                    coinFlip.detailInt(DetailKeys.COIN_FLIP_RESULT).shouldBeInRange(0..1)
                    lifeDeltas.shouldNotBeEmpty()
                    lifeDeltas shouldContain -3
                    if (coinFlip.detailInt(DetailKeys.COIN_FLIP_RESULT) == 1) lifeDeltas shouldContain 6
                }
            }

            assertSoftly {
                prompt.getParameters(0).parameterName shouldBe "PlayerId"
                prompt.getParameters(0).reference.type shouldBe ReferenceType.PlayerSeatId
                prompt.getParameters(0).reference.id shouldBe 1
                prompt.getParameters(1).parameterName shouldBe "CoinFlipResult"
                prompt.getParameters(1).reference.type shouldBe ReferenceType.LocalizationId
            }
        }
    })
