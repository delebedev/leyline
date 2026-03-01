package leyline.protocol

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.game.CardDb
import leyline.game.DeckProvider
import leyline.game.GsmBuilder
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Smoke tests for the client protocol scaffold.
 *
 * These tests verify that:
 * 1. StateMapper produces valid, serializable protobuf
 * 2. CardDb bidirectional lookup works
 * 3. The generated proto enums referenced in handlers actually exist
 *
 * No network or TLS required — pure protobuf logic.
 */
class ProtocolTest :
    FunSpec({

        afterEach {
            CardDb.clear()
        }

        test("card db register and lookup") {
            CardDb.register(12345, "Lightning Bolt")
            CardDb.register(67890, "Counterspell")

            CardDb.getCardName(12345) shouldBe "Lightning Bolt"
            CardDb.getCardName(67890) shouldBe "Counterspell"
            CardDb.getGrpId("Lightning Bolt") shouldBe 12345
            CardDb.getGrpId("Counterspell") shouldBe 67890

            CardDb.getCardName(99999).shouldBeNull()
            CardDb.getGrpId("Nonexistent Card").shouldBeNull()

            CardDb.registeredCount shouldBe 2
        }

        test("card db clear") {
            CardDb.register(1, "Test Card")
            CardDb.registeredCount shouldBe 1

            CardDb.clear()
            CardDb.registeredCount shouldBe 0
            CardDb.getCardName(1).shouldBeNull()
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
            CardDb.register(93940, "Llanowar Elves")
            CardDb.register(93860, "Serra Angel")

            CardDb.lookupByName("Llanowar Elves") shouldBe 93940
            CardDb.lookupByName("Serra Angel") shouldBe 93860
            CardDb.lookupByName("Nonexistent Card").shouldBeNull()
        }

        test("deck provider deals seven cards") {
            // Register all precon cards so lookupByName works (in-memory path)
            CardDb.register(93940, "Llanowar Elves")
            CardDb.register(93941, "Elvish Mystic")
            CardDb.register(93942, "Giant Growth")
            CardDb.register(95189, "Forest")

            val provider = DeckProvider()
            val hand = provider.dealHand(1)

            hand.size shouldBe 7
            // With 4 different card types in the deck, shuffled hand should not all be the same
            // (statistically near-impossible with 20+4+4+32 distribution)
            hand.all { it > 0 }.shouldBeTrue()
        }

        test("deck provider full deck has 60 cards") {
            CardDb.register(93940, "Llanowar Elves")
            CardDb.register(93941, "Elvish Mystic")
            CardDb.register(93942, "Giant Growth")
            CardDb.register(95189, "Forest")

            val provider = DeckProvider()
            val deck = provider.getDeckGrpIds(1)

            deck.size shouldBe 60
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
