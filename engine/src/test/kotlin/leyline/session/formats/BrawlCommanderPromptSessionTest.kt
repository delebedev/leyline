package leyline.session.formats

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.game.mapping.PromptIds
import leyline.testkit.SessionTest
import wotc.mtgo.gre.external.messaging.Messages.CardMechanicType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType

class BrawlCommanderPromptSessionTest :
    SessionTest({
        test("commander return prompt uses zone-transfer optional action and cleans up accepted prompt object") {
            startPuzzleFile("puzzles/commander-return-self-bolt.pzl")
            val commanderName = "Arabella, Abandoned Doll"
            val oldIid = instanceIdOf(commanderName, zone = ZoneType.Battlefield)

            castSpellByName("Lightning Bolt").shouldBeTrue()
            val beforeResolve = messageSnapshot()
            selectTargets(listOf(oldIid))
            passPriority()

            val optionalGre = messagesSince(beforeResolve).firstOrNull { it.type == GREMessageType.OptionalActionMessage_695e }
            optionalGre.shouldNotBeNull()
            val optional = optionalGre.optionalActionMessage
            val recipientId = optional.recipientIdsList.single()

            assertSoftly {
                optionalGre.prompt.promptId shouldBe PromptIds.COMMANDER_RETURN_TO_COMMAND
                optional.optionalActionTypesList shouldContain CardMechanicType.ZoneTransfer_a57f
                optional.recipientIdsList shouldHaveSize 1
                optional.sourceId shouldBe recipientId
                optionalGre.prompt.parametersList.map { it.numberValue } shouldBe listOf(0, recipientId)
                human.getZone(ZoneType.Command).cards.count { it.name == commanderName } shouldBe 1
                harness.accumulator.objects
                    .containsKey(recipientId)
                    .shouldBeFalse()
                assertAccumulatorConsistent("after accepted commander return prompt")
            }
        }

        test("commander return prompt retires the temporary prompt object after a declined response") {
            startPuzzleFile("puzzles/commander-return-self-bolt.pzl")
            val commanderName = "Arabella, Abandoned Doll"
            val oldIid = instanceIdOf(commanderName, zone = ZoneType.Battlefield)

            harness.holdNextOptionalAction()
            castSpellByName("Lightning Bolt").shouldBeTrue()
            val beforeResolve = messageSnapshot()
            selectTargets(listOf(oldIid))

            val optionalGre = messagesSince(beforeResolve).firstOrNull { it.type == GREMessageType.OptionalActionMessage_695e }
            optionalGre.shouldNotBeNull()
            val recipientId = optionalGre.optionalActionMessage.recipientIdsList.single()
            harness.respondToOptionalAction(accept = false)

            assertSoftly {
                optionalGre.prompt.promptId shouldBe PromptIds.COMMANDER_RETURN_TO_COMMAND
                harness.accumulator.objects
                    .containsKey(recipientId)
                    .shouldBeFalse()
                assertAccumulatorConsistent("after declined commander return prompt")
            }
        }
    })
