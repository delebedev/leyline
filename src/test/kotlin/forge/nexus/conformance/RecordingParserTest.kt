package forge.nexus.conformance

import org.testng.Assert.*
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.*

@Test(groups = ["unit"])
class RecordingParserTest {

    @Test
    fun nonGREPayloadReturnsEmpty() {
        // A MatchServiceToClientMessage without greToClientEvent has no GRE messages
        val msg = MatchServiceToClientMessage.newBuilder()
            .setMatchGameRoomStateChangedEvent(
                MatchGameRoomStateChangedEvent.newBuilder().setGameRoomInfo(
                    MatchGameRoomInfo.newBuilder().setStateType(MatchGameRoomStateType.Playing),
                ),
            )
            .build()
        val fps = RecordingParser.parsePayload(msg.toByteArray())
        assertTrue(fps.isEmpty(), "Room state has no GRE messages")
    }

    @Test
    fun grePayloadExtractsFingerprints() {
        val stream = javaClass.classLoader.getResourceAsStream("arena-templates/initial-bundle-seat1.bin")!!
        val fps = RecordingParser.parsePayload(stream.readBytes())
        assertTrue(fps.isNotEmpty(), "Initial bundle should have GRE messages")
        for (fp in fps) {
            assertTrue(fp.greMessageType.isNotEmpty())
        }
    }

    @Test
    fun parseDirectoryCollectsAll() {
        val url = javaClass.classLoader.getResource("arena-templates")!!
        val dir = java.io.File(url.toURI())
        val fps = RecordingParser.parseDirectory(dir)
        assertTrue(fps.isNotEmpty(), "Should extract fingerprints from template dir")
    }
}
