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
import leyline.game.mapping.ZoneIds
import leyline.testkit.SessionTest
import leyline.testkit.findZoneTransfer
import leyline.testkit.lastGsmMatching
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

            val promptMessages = messagesSince(beforeResolve)
            val optionalGre = promptMessages.firstOrNull { it.type == GREMessageType.OptionalActionMessage_695e }
            optionalGre.shouldNotBeNull()
            val optional = optionalGre.optionalActionMessage
            val recipientId = optional.recipientIdsList.single()
            val transfer = promptMessages.lastGsmMatching { it.findZoneTransfer(recipientId) != null }?.findZoneTransfer(recipientId)
            transfer.shouldNotBeNull()

            assertSoftly {
                optionalGre.prompt.promptId shouldBe PromptIds.COMMANDER_RETURN_TO_COMMAND
                optional.optionalActionTypesList shouldContain CardMechanicType.ZoneTransfer_a57f
                optional.recipientIdsList shouldHaveSize 1
                optional.sourceId shouldBe recipientId
                optionalGre.prompt.parametersList.map { it.numberValue } shouldBe listOf(0, recipientId)
                transfer.category shouldBe "Destroy"
                transfer.zoneSrc shouldBe ZoneIds.BATTLEFIELD
                transfer.zoneDest shouldBe ZoneIds.P1_GRAVEYARD
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
                human.getZone(ZoneType.Graveyard).cards.map { it.name } shouldContain commanderName
                harness.accumulator.objects
                    .containsKey(recipientId)
                    .shouldBeFalse()
                assertAccumulatorConsistent("after declined commander return prompt")
            }
        }

        test("commander moving to hand keeps the replacement prompt") {
            startPuzzleFile("puzzles/commander-return-unsummon.pzl")
            val commanderName = "Arabella, Abandoned Doll"
            val oldIid = instanceIdOf(commanderName, zone = ZoneType.Battlefield)

            harness.holdNextOptionalAction()
            castSpellByName("Unsummon").shouldBeTrue()
            val beforeResolve = messageSnapshot()
            selectTargets(listOf(oldIid))

            val promptMessages = messagesSince(beforeResolve)
            val optionalGre = promptMessages.firstOrNull { it.type == GREMessageType.OptionalActionMessage_695e }
            optionalGre.shouldNotBeNull()
            harness.respondToOptionalAction(accept = false)
            val recipientId = optionalGre.optionalActionMessage.recipientIdsList.single()
            val transfer = promptMessages.lastGsmMatching { it.findZoneTransfer(recipientId) != null }?.findZoneTransfer(recipientId)
            transfer.shouldNotBeNull()

            assertSoftly {
                optionalGre.prompt.promptId shouldBe PromptIds.COMMANDER_RETURN_TO_COMMAND
                transfer.category shouldBe "Bounce"
                transfer.zoneSrc shouldBe ZoneIds.BATTLEFIELD
                transfer.zoneDest shouldBe ZoneIds.P1_HAND
                human.getZone(ZoneType.Hand).cards.map { it.name } shouldContain commanderName
                assertAccumulatorConsistent("after declined commander hand replacement")
            }
        }

        test("commander reaching exile uses the state-based return prompt") {
            startPuzzleFile("puzzles/commander-return-swords.pzl")
            val commanderName = "Arabella, Abandoned Doll"
            val oldIid = instanceIdOf(commanderName, zone = ZoneType.Battlefield)

            harness.holdNextOptionalAction()
            castSpellByName("Swords to Plowshares").shouldBeTrue()
            val beforeResolve = messageSnapshot()
            selectTargets(listOf(oldIid))

            val promptMessages = messagesSince(beforeResolve)
            val optionalGre = promptMessages.firstOrNull { it.type == GREMessageType.OptionalActionMessage_695e }
            optionalGre.shouldNotBeNull()
            val recipientId = optionalGre.optionalActionMessage.recipientIdsList.single()
            val transfer = promptMessages.lastGsmMatching { it.findZoneTransfer(recipientId) != null }?.findZoneTransfer(recipientId)
            transfer.shouldNotBeNull()
            harness.respondToOptionalAction(accept = true)

            assertSoftly {
                optionalGre.prompt.promptId shouldBe PromptIds.COMMANDER_RETURN_TO_COMMAND
                transfer.category shouldBe "Exile"
                transfer.zoneDest shouldBe ZoneIds.EXILE
                human.getZone(ZoneType.Command).cards.map { it.name } shouldContain commanderName
                assertAccumulatorConsistent("after accepted commander exile return")
            }
        }
    })
