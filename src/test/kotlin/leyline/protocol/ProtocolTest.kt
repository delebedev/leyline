package leyline.protocol

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.game.GsmBuilder
import leyline.game.InMemoryCardRepository
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Smoke tests for the client protocol scaffold.
 *
 * These tests verify that:
 * 1. StateMapper produces valid, serializable protobuf
 * 2. InMemoryCardRepository bidirectional lookup works
 * 3. The generated proto enums referenced in handlers actually exist
 *
 * No network or TLS required — pure protobuf logic.
 */
class ProtocolTest :
    FunSpec({

        tags(UnitTag)

        lateinit var cards: InMemoryCardRepository

        beforeEach {
            cards = InMemoryCardRepository()
        }

        test("card repository register and lookup") {
            cards.register(12345, "Lightning Bolt")
            cards.register(67890, "Counterspell")

            cards.findNameByGrpId(12345) shouldBe "Lightning Bolt"
            cards.findNameByGrpId(67890) shouldBe "Counterspell"
            cards.findGrpIdByName("Lightning Bolt") shouldBe 12345
            cards.findGrpIdByName("Counterspell") shouldBe 67890

            cards.findNameByGrpId(99999).shouldBeNull()
            cards.findGrpIdByName("Nonexistent Card").shouldBeNull()

            cards.registeredCount shouldBe 2
        }

        test("card repository clear") {
            cards.register(1, "Test Card")
            cards.registeredCount shouldBe 1

            cards.clear()
            cards.registeredCount shouldBe 0
            cards.findNameByGrpId(1).shouldBeNull()
        }

        test("gre envelope wraps game state") {
            // Verify we can build the full GRE -> MatchService envelope
            val gameState = GameStateMessage.newBuilder()
                .setType(GameStateType.Full)
                .setGameStateId(1)
                .addPlayers(PlayerInfo.newBuilder().setSystemSeatNumber(1).setLifeTotal(20))
                .addPlayers(PlayerInfo.newBuilder().setSystemSeatNumber(2).setLifeTotal(20))
                .build()

            val greToClient = GREToClientMessage.newBuilder()
                .setType(GREMessageType.GameStateMessage_695e)
                .setMsgId(1)
                .setGameStateId(1)
                .addSystemSeatIds(1)
                .addSystemSeatIds(2)
                .setGameStateMessage(gameState)
                .build()

            val event = GreToClientEvent.newBuilder()
                .addGreToClientMessages(greToClient)
                .build()

            val response = MatchServiceToClientMessage.newBuilder()
                .setGreToClientEvent(event)
                .build()

            // Round-trip the entire envelope
            val bytes = response.toByteArray()
            val parsed = MatchServiceToClientMessage.parseFrom(bytes)

            parsed.hasGreToClientEvent().shouldBeTrue()
            parsed.greToClientEvent.greToClientMessagesCount shouldBe 1

            val innerGre = parsed.greToClientEvent.getGreToClientMessages(0)
            innerGre.type shouldBe GREMessageType.GameStateMessage_695e
            innerGre.hasGameStateMessage().shouldBeTrue()
            innerGre.gameStateMessage.playersCount shouldBe 2
        }

        test("connect resp builds correctly") {
            val connectResp = ConnectResp.newBuilder()
                .setStatus(ConnectionStatus.Success_aa9e)
                .setProtoVer(ProtoVersion.PersistentAnnotations)
                .build()

            val greToClient = GREToClientMessage.newBuilder()
                .setType(GREMessageType.ConnectResp_695e)
                .setMsgId(1)
                .setConnectResp(connectResp)
                .build()

            val bytes = greToClient.toByteArray()
            val parsed = GREToClientMessage.parseFrom(bytes)

            parsed.type shouldBe GREMessageType.ConnectResp_695e
            parsed.connectResp.status shouldBe ConnectionStatus.Success_aa9e
            parsed.connectResp.protoVer shouldBe ProtoVersion.PersistentAnnotations
        }

        test("lookup by name uses in memory cache") {
            cards.register(93940, "Llanowar Elves")
            cards.register(93860, "Serra Angel")

            cards.findGrpIdByName("Llanowar Elves") shouldBe 93940
            cards.findGrpIdByName("Serra Angel") shouldBe 93860
            cards.findGrpIdByName("Nonexistent Card").shouldBeNull()
        }

        test("build deck message from grp id list") {
            val grpIds = listOf(100, 100, 200, 200, 300)
            val msg = GsmBuilder.buildDeckMessage(grpIds)

            msg.deckCardsCount shouldBe 5
            msg.getDeckCards(0) shouldBe 100
            msg.getDeckCards(2) shouldBe 200
            msg.getDeckCards(4) shouldBe 300
        }

        test("auth response builds correctly") {
            val authResp = AuthenticateResponse.newBuilder()
                .setClientId("forge-client-1")
                .setSessionId("forge-session-1")
                .setScreenName("ForgePlayer")
                .build()

            val response = MatchServiceToClientMessage.newBuilder()
                .setRequestId(42)
                .setAuthenticateResponse(authResp)
                .build()

            val bytes = response.toByteArray()
            val parsed = MatchServiceToClientMessage.parseFrom(bytes)

            parsed.requestId shouldBe 42
            parsed.hasAuthenticateResponse().shouldBeTrue()
            parsed.authenticateResponse.clientId shouldBe "forge-client-1"
            parsed.authenticateResponse.screenName shouldBe "ForgePlayer"
        }
    })
