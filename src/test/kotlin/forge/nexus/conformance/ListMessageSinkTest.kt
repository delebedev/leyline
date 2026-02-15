package forge.nexus.conformance

import forge.nexus.server.ListMessageSink
import org.testng.Assert.*
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.*

@Test(groups = ["unit"])
class ListMessageSinkTest {

    @Test(description = "send() captures GRE messages into list")
    fun sendCapturesMessages() {
        val sink = ListMessageSink()
        val gre = GREToClientMessage.newBuilder()
            .setType(GREMessageType.GameStateMessage_695e)
            .setMsgId(1)
            .build()
        sink.send(listOf(gre))
        assertEquals(sink.messages.size, 1)
        assertEquals(sink.messages[0], gre)
    }

    @Test(description = "sendRaw() captures raw MatchServiceToClientMessage")
    fun sendRawCaptures() {
        val sink = ListMessageSink()
        val raw = MatchServiceToClientMessage.newBuilder()
            .setRequestId(42)
            .build()
        sink.sendRaw(raw)
        assertEquals(sink.rawMessages.size, 1)
        assertEquals(sink.rawMessages[0].requestId, 42)
    }

    @Test(description = "clear() resets both lists")
    fun clearResetsBothLists() {
        val sink = ListMessageSink()
        sink.send(listOf(GREToClientMessage.getDefaultInstance()))
        sink.sendRaw(MatchServiceToClientMessage.getDefaultInstance())
        sink.clear()
        assertTrue(sink.messages.isEmpty())
        assertTrue(sink.rawMessages.isEmpty())
    }
}
