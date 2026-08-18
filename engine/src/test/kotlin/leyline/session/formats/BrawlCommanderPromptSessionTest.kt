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
import leyline.testkit.assertAccumulatorConsistent
import leyline.testkit.findZoneTransfer
import leyline.testkit.lastGsmMatching
import wotc.mtgo.gre.external.messaging.Messages.CardMechanicType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType

private val COMMANDER_RETURN_UNSUMMON_PUZZLE =
    """
    [metadata]
    Name:Commander Return Unsummon
    Goal:Move a commander toward its owner's hand and choose whether to return it to command.
    Turns:3
    Difficulty:Tutorial
    Description:Arabella starts on the battlefield as the human commander with Unsummon ready.

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=25
    AILife=25

    humanhand=Unsummon
    humanbattlefield=Arabella, Abandoned Doll|IsCommander;Island;Island
    humanlibrary=Island;Island;Island;Island;Island
    aibattlefield=Mountain;Mountain
    ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
    """.trimIndent()

private val COMMANDER_RETURN_SWORDS_PUZZLE =
    """
    [metadata]
    Name:Commander Return Swords
    Goal:Exile a commander, then return it to command through the state-based choice.
    Turns:3
    Difficulty:Tutorial
    Description:Arabella starts on the battlefield as the human commander with Swords to Plowshares ready.

    [state]
    ActivePlayer=Human
    ActivePhase=Main1
    HumanLife=25
    AILife=25

    humanhand=Swords to Plowshares
    humanbattlefield=Arabella, Abandoned Doll|IsCommander;Plains;Plains
    humanlibrary=Plains;Plains;Plains;Plains;Plains
    aibattlefield=Mountain;Mountain
    ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
    """.trimIndent()

class BrawlCommanderPromptSessionTest :
    SessionTest({
        session(
            "commander return prompt uses zone-transfer optional action and cleans up accepted prompt object",
            puzzleFile = "puzzles/commander-return-self-bolt.pzl",
        ) {
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
                accumulator.objects
                    .containsKey(recipientId)
                    .shouldBeFalse()
                assertAccumulatorConsistent("after accepted commander return prompt")
            }
        }

        session(
            "commander return prompt retires the temporary prompt object after a declined response",
            puzzleFile = "puzzles/commander-return-self-bolt.pzl",
        ) {
            val commanderName = "Arabella, Abandoned Doll"
            val oldIid = instanceIdOf(commanderName, zone = ZoneType.Battlefield)

            holdNextOptionalAction()
            castSpellByName("Lightning Bolt").shouldBeTrue()
            val beforeResolve = messageSnapshot()
            selectTargets(listOf(oldIid))

            val optionalGre = messagesSince(beforeResolve).firstOrNull { it.type == GREMessageType.OptionalActionMessage_695e }
            optionalGre.shouldNotBeNull()
            val recipientId = optionalGre.optionalActionMessage.recipientIdsList.single()
            respondToOptionalAction(accept = false)

            assertSoftly {
                optionalGre.prompt.promptId shouldBe PromptIds.COMMANDER_RETURN_TO_COMMAND
                human.getZone(ZoneType.Graveyard).cards.map { it.name } shouldContain commanderName
                accumulator.objects
                    .containsKey(recipientId)
                    .shouldBeFalse()
                assertAccumulatorConsistent("after declined commander return prompt")
            }
        }

        session("commander moving to hand keeps the replacement prompt", puzzle = COMMANDER_RETURN_UNSUMMON_PUZZLE) {
            val commanderName = "Arabella, Abandoned Doll"
            val oldIid = instanceIdOf(commanderName, zone = ZoneType.Battlefield)

            holdNextOptionalAction()
            castSpellByName("Unsummon").shouldBeTrue()
            val beforeResolve = messageSnapshot()
            selectTargets(listOf(oldIid))

            val promptMessages = messagesSince(beforeResolve)
            val optionalGre = promptMessages.firstOrNull { it.type == GREMessageType.OptionalActionMessage_695e }
            optionalGre.shouldNotBeNull()
            respondToOptionalAction(accept = false)
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

        session("commander reaching exile uses the state-based return prompt", puzzle = COMMANDER_RETURN_SWORDS_PUZZLE) {
            val commanderName = "Arabella, Abandoned Doll"
            val oldIid = instanceIdOf(commanderName, zone = ZoneType.Battlefield)

            holdNextOptionalAction()
            castSpellByName("Swords to Plowshares").shouldBeTrue()
            val beforeResolve = messageSnapshot()
            selectTargets(listOf(oldIid))

            val promptMessages = messagesSince(beforeResolve)
            val optionalGre = promptMessages.firstOrNull { it.type == GREMessageType.OptionalActionMessage_695e }
            optionalGre.shouldNotBeNull()
            val recipientId = optionalGre.optionalActionMessage.recipientIdsList.single()
            val transfer = promptMessages.lastGsmMatching { it.findZoneTransfer(recipientId) != null }?.findZoneTransfer(recipientId)
            transfer.shouldNotBeNull()
            respondToOptionalAction(accept = true)

            assertSoftly {
                optionalGre.prompt.promptId shouldBe PromptIds.COMMANDER_RETURN_TO_COMMAND
                transfer.category shouldBe "Exile"
                transfer.zoneDest shouldBe ZoneIds.EXILE
                human.getZone(ZoneType.Command).cards.map { it.name } shouldContain commanderName
                assertAccumulatorConsistent("after accepted commander exile return")
            }
        }
    })
