package leyline.game.state

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import forge.game.ability.ApiType
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.shouldBe
import leyline.bridge.handoff.GameActionBridge
import leyline.bridge.handoff.IndistinguishableOffer
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.ForgeCardId
import leyline.testkit.BoardTest
import org.slf4j.LoggerFactory
import wotc.mtgo.gre.external.messaging.Messages.Action
import wotc.mtgo.gre.external.messaging.Messages.ActionType

class AbilityIdentityLedgerTest :
    BoardTest({

        test("a dropped offer whose ability differs from the kept one logs at error") {
            lateinit var cheap: forge.game.spellability.SpellAbility
            lateinit var costly: forge.game.spellability.SpellAbility
            startWithBoard { _, human, _ ->
                cheap = addCard("Bonesplitter", human, ZoneType.Battlefield).spellAbilities.single { it.api == ApiType.Attach }
                costly = addCard("Colossus Hammer", human, ZoneType.Battlefield).spellAbilities.single { it.api == ApiType.Attach }
            }
            val action =
                Action
                    .newBuilder()
                    .setActionType(ActionType.Activate_add3)
                    .setInstanceId(100)
                    .build()

            fun offer(ability: forge.game.spellability.SpellAbility) =
                GameActionBridge.ActionOffer(
                    action,
                    PlayerAction.ActivateAbility(ForgeCardId(ability.hostCard.id), ability.id, ability = ability),
                )
            val ledger = AbilityIdentityLedger()
            val log = LoggerFactory.getLogger(AbilityIdentityLedger::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            log.addAppender(appender)

            try {
                ledger.recordIndistinguishableOffers(listOf(IndistinguishableOffer(offer(cheap), offer(costly))))
            } finally {
                log.detachAppender(appender)
            }

            assertSoftly {
                appender.list.map { it.level } shouldBe listOf(Level.ERROR)
                ledger.ambiguousCatalogs().values.sum() shouldBe 1
            }
        }
    })
