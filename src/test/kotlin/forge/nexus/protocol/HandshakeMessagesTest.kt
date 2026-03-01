package forge.nexus.protocol

import forge.nexus.game.GameBridge
import forge.util.MyRandom
import forge.web.game.GameBootstrap
import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.AfterMethod
import org.testng.annotations.BeforeClass
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.DeckMessage
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import java.util.Random

/** Integration tests for [HandshakeMessages] — die roll determinism and range. */
@Test(groups = ["integration"])
class HandshakeMessagesTest {

    @BeforeClass(alwaysRun = true)
    fun initCardDatabase() {
        GameBootstrap.initializeCardDatabase(quiet = true)
    }

    private lateinit var bridge: GameBridge

    @AfterMethod
    fun tearDown() {
        if (::bridge.isInitialized) bridge.shutdown()
    }

    private fun extractDieRolls(b: GameBridge, winner: Int = 2): Map<Int, Int> {
        val bundle = HandshakeMessages.initialBundle(
            seatId = 2,
            matchId = "test",
            msgIdStart = 1,
            gameStateId = 1,
            deckMessage = DeckMessage.getDefaultInstance(),
            bridge = b,
            dieRollWinner = winner,
        )
        val dieRoll = bundle.first.greToClientEvent.greToClientMessagesList
            .first { it.type == GREMessageType.DieRollResultsResp_695e }
            .dieRollResultsResp
        return dieRoll.playerDieRollsList.associate { it.systemSeatId to it.rollValue }
    }

    /** Die roll: winner seat always rolls higher than loser, values 1..20. */
    @Test
    fun dieRollWinnerRollsHigher() {
        val b = GameBridge()
        bridge = b
        b.start(seed = 1L)
        // Test multiple seeds — die roll uses MyRandom which we can re-seed
        repeat(10) { i ->
            MyRandom.setRandom(Random(i.toLong()))
            val rolls = extractDieRolls(b, winner = 2)
            assertTrue(rolls[1]!! in 1..20, "Seat 1 roll should be 1..20, was ${rolls[1]} (seed=$i)")
            assertTrue(rolls[2]!! in 1..20, "Seat 2 roll should be 1..20, was ${rolls[2]} (seed=$i)")
            assertTrue(rolls[2]!! > rolls[1]!!, "Winner (seat 2) should roll higher: ${rolls[2]} vs ${rolls[1]} (seed=$i)")
        }
    }

    /** Die roll is deterministic when MyRandom is seeded. */
    @Test
    fun dieRollDeterministicWithSeed() {
        val b = GameBridge()
        bridge = b
        b.start(seed = 1L)

        MyRandom.setRandom(Random(42))
        val first = extractDieRolls(b)

        MyRandom.setRandom(Random(42))
        val second = extractDieRolls(b)

        assertEquals(first, second, "Same seed should produce same die roll values")
    }
}
