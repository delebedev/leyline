package leyline.conformance

import leyline.recording.RecordingDecoder
import org.testng.Assert.assertEquals
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateType
import wotc.mtgo.gre.external.messaging.Messages.GreToClientEvent
import wotc.mtgo.gre.external.messaging.Messages.MatchServiceToClientMessage
import java.io.File
import java.nio.file.Files

@Test(groups = ["unit"])
class RecordingDecoderCompatTest {

    @Test
    fun parseMatchMessageAcceptsClientFrame() {
        val payload = buildMessage(gsId = 77).toByteArray()
        val framed = arenaFrame(payload)

        val parsed = checkNotNull(RecordingDecoder.parseMatchMessage(framed))
        assertEquals(parsed.greToClientEvent.greToClientMessagesList.first().gameStateId, 77)
    }

    @Test
    fun decodeDirectoryReadsGenericBinNames() {
        val dir = Files.createTempDirectory("recording-decoder-compat").toFile()
        try {
            File(dir, "001-engine.bin").writeBytes(buildMessage(gsId = 1).toByteArray())
            File(dir, "002-proxy-frame.bin").writeBytes(arenaFrame(buildMessage(gsId = 2).toByteArray()))

            val decoded = RecordingDecoder.decodeDirectory(dir, seatFilter = null)
            assertEquals(decoded.size, 2)
            assertEquals(decoded[0].gsId, 1)
            assertEquals(decoded[1].gsId, 2)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun buildMessage(gsId: Int): MatchServiceToClientMessage {
        val gre = GREToClientMessage.newBuilder()
            .setType(GREMessageType.GameStateMessage_695e)
            .setMsgId(gsId)
            .setGameStateId(gsId)
            .addSystemSeatIds(1)
            .setGameStateMessage(
                GameStateMessage.newBuilder()
                    .setType(GameStateType.Diff)
                    .setGameStateId(gsId),
            )
            .build()

        return MatchServiceToClientMessage.newBuilder()
            .setGreToClientEvent(GreToClientEvent.newBuilder().addGreToClientMessages(gre))
            .build()
    }

    private fun arenaFrame(payload: ByteArray): ByteArray {
        val out = ByteArray(6 + payload.size)
        out[0] = 0x04
        out[1] = 0x11
        out[2] = (payload.size and 0xff).toByte()
        out[3] = ((payload.size shr 8) and 0xff).toByte()
        out[4] = ((payload.size shr 16) and 0xff).toByte()
        out[5] = ((payload.size shr 24) and 0xff).toByte()
        payload.copyInto(out, destinationOffset = 6)
        return out
    }
}
