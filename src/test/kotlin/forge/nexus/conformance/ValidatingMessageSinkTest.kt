package forge.nexus.conformance

import forge.nexus.server.ListMessageSink
import org.testng.Assert.*
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.*

/**
 * Unit tests for [ValidatingMessageSink].
 *
 * Hand-built proto messages that trigger each invariant violation.
 * No engine boot required.
 */
@Test(groups = ["unit"])
class ValidatingMessageSinkTest {

    // --- Helpers ---

    private fun gre(
        msgId: Int = 1,
        gsId: Int = 0,
        configure: GREToClientMessage.Builder.() -> Unit = {},
    ): GREToClientMessage =
        GREToClientMessage.newBuilder()
            .setType(GREMessageType.GameStateMessage_695e)
            .setMsgId(msgId)
            .setGameStateId(gsId)
            .apply(configure)
            .build()

    private fun gsm(
        gsId: Int,
        prevGsId: Int = 0,
        type: GameStateType = GameStateType.Diff,
        pendingMessageCount: Int = 0,
        update: GameStateUpdate = GameStateUpdate.None_a0c7,
        annotations: List<AnnotationInfo> = emptyList(),
    ): GameStateMessage =
        GameStateMessage.newBuilder()
            .setGameStateId(gsId)
            .setPrevGameStateId(prevGsId)
            .setType(type)
            .setPendingMessageCount(pendingMessageCount)
            .setUpdate(update)
            .addAllAnnotations(annotations)
            .build()

    private fun annotation(id: Int, type: AnnotationType = AnnotationType.None_af5a): AnnotationInfo =
        AnnotationInfo.newBuilder()
            .setId(id)
            .addType(type)
            .build()

    private fun greWithGsm(
        msgId: Int,
        gsm: GameStateMessage,
    ): GREToClientMessage =
        GREToClientMessage.newBuilder()
            .setType(GREMessageType.GameStateMessage_695e)
            .setMsgId(msgId)
            .setGameStateId(gsm.gameStateId)
            .setGameStateMessage(gsm)
            .build()

    private fun lenientSink() = ValidatingMessageSink(strict = false)
    private fun strictSink() = ValidatingMessageSink(strict = true)

    // --- Positive: clean stream ---

    @Test(description = "Clean message stream produces no violations")
    fun cleanStreamNoViolations() {
        val sink = lenientSink()

        val gsm1 = gsm(gsId = 1, type = GameStateType.Full, annotations = listOf(annotation(1)))
        val gsm2 = gsm(gsId = 2, prevGsId = 1, annotations = listOf(annotation(1), annotation(2)))
        val gsm3 = gsm(gsId = 3, prevGsId = 2)

        sink.send(listOf(greWithGsm(1, gsm1)))
        sink.send(listOf(greWithGsm(2, gsm2)))
        sink.send(listOf(greWithGsm(3, gsm3)))

        assertTrue(sink.violations.isEmpty(), "Expected no violations, got: ${sink.violations}")
        assertEquals(sink.messages.size, 3)
        sink.assertClean()
    }

    // --- gsId monotonicity ---

    @Test(description = "Detects non-monotonic gsId")
    fun gsIdNotMonotonic() {
        val sink = lenientSink()

        val gsm1 = gsm(gsId = 5, type = GameStateType.Full)
        val gsm2 = gsm(gsId = 3) // violation: 3 < 5

        sink.send(listOf(greWithGsm(1, gsm1)))
        sink.send(listOf(greWithGsm(2, gsm2)))

        assertTrue(sink.violations.any { "gsId not monotonic" in it }, "Expected gsId monotonicity violation: ${sink.violations}")
    }

    @Test(description = "gsId monotonicity throws in strict mode")
    fun gsIdNotMonotonicStrict() {
        val sink = strictSink()
        val gsm1 = gsm(gsId = 5, type = GameStateType.Full)

        sink.send(listOf(greWithGsm(1, gsm1)))

        try {
            sink.send(listOf(greWithGsm(2, gsm(gsId = 3))))
            fail("Expected AssertionError")
        } catch (e: AssertionError) {
            assertTrue("gsId not monotonic" in e.message!!)
        }
    }

    // --- prevGsId validity ---

    @Test(description = "Detects prevGsId referencing unknown gsId")
    fun prevGsIdUnknown() {
        val sink = lenientSink()

        val gsm1 = gsm(gsId = 1, type = GameStateType.Full)
        val gsm2 = gsm(gsId = 2, prevGsId = 99) // violation: 99 never seen

        sink.send(listOf(greWithGsm(1, gsm1)))
        sink.send(listOf(greWithGsm(2, gsm2)))

        assertTrue(sink.violations.any { "prevGsId 99 not in known set" in it }, "Expected prevGsId violation: ${sink.violations}")
    }

    // --- No self-referential gsId ---

    @Test(description = "Detects self-referential gsId == prevGsId")
    fun selfReferentialGsId() {
        val sink = lenientSink()

        // Seed gsId=5 first so monotonicity passes
        val seed = gsm(gsId = 5, type = GameStateType.Full)
        sink.send(listOf(greWithGsm(1, seed)))

        val bad = gsm(gsId = 7, prevGsId = 7) // violation: self-ref
        sink.send(listOf(greWithGsm(2, bad)))

        assertTrue(sink.violations.any { "Self-referential gsId" in it }, "Expected self-ref violation: ${sink.violations}")
    }

    // --- msgId monotonicity ---

    @Test(description = "Detects non-monotonic msgId")
    fun msgIdNotMonotonic() {
        val sink = lenientSink()

        val msg1 = gre(msgId = 5)
        val msg2 = gre(msgId = 3) // violation: 3 < 5

        sink.send(listOf(msg1))
        sink.send(listOf(msg2))

        assertTrue(sink.violations.any { "msgId not monotonic" in it }, "Expected msgId monotonicity violation: ${sink.violations}")
    }

    // --- Annotation ID sequentiality ---

    @Test(description = "Detects non-sequential annotation IDs")
    fun annotationIdsNotSequential() {
        val sink = lenientSink()

        // IDs must be contiguous: 1,5 has a gap (expected 2 after 1)
        val badGsm = gsm(
            gsId = 1,
            type = GameStateType.Full,
            annotations = listOf(annotation(1), annotation(5)),
        )

        sink.send(listOf(greWithGsm(1, badGsm)))

        assertTrue(sink.violations.any { "Annotation IDs not sequential" in it }, "Expected annotation violation: ${sink.violations}")
    }

    @Test(description = "Sequential annotation IDs starting from arbitrary value are OK")
    fun annotationIdsSequentialFromArbitraryStart() {
        val sink = lenientSink()

        // IDs 50,51,52 — contiguous, just not starting from 1
        val goodGsm = gsm(
            gsId = 1,
            type = GameStateType.Full,
            annotations = listOf(annotation(50), annotation(51), annotation(52)),
        )

        sink.send(listOf(greWithGsm(1, goodGsm)))

        assertTrue(sink.violations.isEmpty(), "Contiguous IDs should not violate: ${sink.violations}")
    }

    @Test(description = "Detects zero annotation ID in mixed-id GSM")
    fun annotationIdZero() {
        val sink = lenientSink()

        // Mix of assigned and unassigned IDs — id=0 among non-zero triggers violation
        val badGsm = gsm(
            gsId = 1,
            type = GameStateType.Full,
            annotations = listOf(annotation(1), annotation(0)), // violation: id=0 mixed with assigned
        )

        sink.send(listOf(greWithGsm(1, badGsm)))

        assertTrue(sink.violations.any { "id=0" in it }, "Expected zero annotation ID violation: ${sink.violations}")
    }

    // --- Action instanceId consistency ---

    @Test(description = "Detects action instanceId missing from objects")
    fun actionInstanceIdMissing() {
        val sink = lenientSink()

        // Send a Full GSM with no objects
        val fullGsm = gsm(gsId = 1, type = GameStateType.Full)
        sink.send(listOf(greWithGsm(1, fullGsm)))

        // Send AAR referencing instanceId=999 which doesn't exist
        val aar = ActionsAvailableReq.newBuilder()
            .addActions(Action.newBuilder().setActionType(ActionType.Play_add3).setInstanceId(999))
            .build()
        val aarMsg = GREToClientMessage.newBuilder()
            .setType(GREMessageType.ActionsAvailableReq_695e)
            .setMsgId(2)
            .setActionsAvailableReq(aar)
            .build()

        sink.send(listOf(aarMsg))

        assertTrue(sink.violations.any { "Action instanceIds missing" in it }, "Expected action instanceId violation: ${sink.violations}")
    }

    // --- Zone-object consistency ---

    @Test(description = "Detects zone object missing from objects map")
    fun zoneObjectMissing() {
        val sink = lenientSink()

        // Full GSM with a visible zone referencing instanceId=42, but no matching object
        val zone = ZoneInfo.newBuilder()
            .setZoneId(1)
            .setType(ZoneType.Battlefield)
            .setVisibility(Visibility.Public)
            .addObjectInstanceIds(42)
            .build()
        val fullGsm = GameStateMessage.newBuilder()
            .setGameStateId(1)
            .setType(GameStateType.Full)
            .addZones(zone)
            .build()

        sink.send(listOf(greWithGsm(1, fullGsm)))

        assertTrue(sink.violations.any { "Zone objects missing" in it }, "Expected zone object violation: ${sink.violations}")
    }

    @Test(description = "Hidden/Private/Limbo zones are skipped for zone-object check")
    fun hiddenZonesSkipped() {
        val sink = lenientSink()

        // Zones with objectInstanceIds but no matching objects — should be OK for hidden/private/limbo
        val hiddenZone = ZoneInfo.newBuilder()
            .setZoneId(1).setType(ZoneType.Library).setVisibility(Visibility.Hidden)
            .addObjectInstanceIds(100)
            .build()
        val privateZone = ZoneInfo.newBuilder()
            .setZoneId(2).setType(ZoneType.Hand).setVisibility(Visibility.Private)
            .addObjectInstanceIds(200)
            .build()
        val limboZone = ZoneInfo.newBuilder()
            .setZoneId(3).setType(ZoneType.Limbo).setVisibility(Visibility.Public)
            .addObjectInstanceIds(300)
            .build()

        val fullGsm = GameStateMessage.newBuilder()
            .setGameStateId(1)
            .setType(GameStateType.Full)
            .addZones(hiddenZone)
            .addZones(privateZone)
            .addZones(limboZone)
            .build()

        sink.send(listOf(greWithGsm(1, fullGsm)))

        assertTrue(sink.violations.isEmpty(), "Hidden/Private/Limbo zones should not trigger violations: ${sink.violations}")
    }

    // --- pendingMessageCount contract ---

    // TODO: pendingMessageCount check is commented out in InvariantChecker (too strict).
    // Re-enable this test when the diff pipeline guarantees correct pending counts.
    // @Test(description = "Detects SendAndRecord arriving before pending countdown completes")
    // fun pendingMessageCountViolation() { ... }

    // --- Delegation ---

    @Test(description = "Messages are captured in inner sink")
    fun delegatesToInnerSink() {
        val inner = ListMessageSink()
        val sink = ValidatingMessageSink(inner, strict = false)

        val msg = gre(msgId = 1)
        sink.send(listOf(msg))

        assertEquals(inner.messages.size, 1)
        assertEquals(inner.messages[0], msg)
        assertEquals(sink.messages.size, 1)
    }

    @Test(description = "sendRaw delegates to inner sink")
    fun sendRawDelegates() {
        val sink = lenientSink()
        val raw = MatchServiceToClientMessage.newBuilder().setRequestId(42).build()

        sink.sendRaw(raw)

        assertEquals(sink.rawMessages.size, 1)
        assertEquals(sink.rawMessages[0].requestId, 42)
    }

    @Test(description = "clear delegates to inner sink")
    fun clearDelegates() {
        val sink = lenientSink()
        sink.send(listOf(gre(msgId = 1)))
        sink.sendRaw(MatchServiceToClientMessage.getDefaultInstance())

        sink.clear()

        assertTrue(sink.messages.isEmpty())
        assertTrue(sink.rawMessages.isEmpty())
    }

    // --- assertClean ---

    @Test(description = "assertClean throws when violations exist")
    fun assertCleanThrows() {
        val sink = lenientSink()

        // Force a violation
        sink.send(listOf(gre(msgId = 5)))
        sink.send(listOf(gre(msgId = 3)))

        try {
            sink.assertClean()
            fail("Expected AssertionError from assertClean")
        } catch (e: AssertionError) {
            assertTrue("violation" in e.message!!.lowercase())
        }
    }

    // --- seedFull ---

    @Test(description = "seedFull populates gsId tracking so subsequent diffs validate correctly")
    fun seedFullTracksGsId() {
        val sink = lenientSink()

        val fullGsm = gsm(gsId = 10, type = GameStateType.Full)
        sink.seedFull(fullGsm)

        // Diff referencing prevGsId=10 should be fine
        val diffGsm = gsm(gsId = 11, prevGsId = 10)
        sink.send(listOf(greWithGsm(1, diffGsm)))

        assertTrue(sink.violations.isEmpty(), "Seeded gsId should be recognized: ${sink.violations}")
    }
}
