package leyline.conformance

import org.testng.Assert.*
import org.testng.annotations.Test

@Test(groups = ["unit"])
class StructuralDiffTest {

    private val gsFingerprint = StructuralFingerprint(
        greMessageType = "GameStateMessage",
        gsType = "Diff",
        updateType = "SendAndRecord",
        annotationTypes = listOf("ZoneTransfer"),
        annotationCategories = listOf("PlayLand"),
        fieldPresence = setOf("turnInfo", "zones", "objects"),
        zoneCount = 2,
        objectCount = 1,
        actionTypes = emptyList(),
        hasPrompt = false,
        promptId = null,
    )

    private val actionsFingerprint = StructuralFingerprint(
        greMessageType = "ActionsAvailableReq",
        gsType = null,
        updateType = null,
        annotationTypes = emptyList(),
        annotationCategories = emptyList(),
        fieldPresence = emptySet(),
        zoneCount = 0,
        objectCount = 0,
        actionTypes = listOf("Pass", "Play"),
        hasPrompt = true,
        promptId = 2,
    )

    @Test
    fun identicalSequencesMatch() {
        val seq = listOf(gsFingerprint, actionsFingerprint)
        val result = StructuralDiff.compare(seq, seq)
        assertTrue(result.matches)
        assertTrue(result.divergences.isEmpty())
    }

    @Test
    fun differentLengthsReported() {
        val result = StructuralDiff.compare(
            listOf(gsFingerprint, actionsFingerprint),
            listOf(gsFingerprint),
        )
        assertFalse(result.matches)
        assertTrue(result.report().contains("length"))
    }

    @Test
    fun missingAnnotationReported() {
        val result = StructuralDiff.compare(
            listOf(
                gsFingerprint.copy(
                    annotationTypes = listOf("ResolutionComplete", "ResolutionStart", "ZoneTransfer"),
                    annotationCategories = listOf("Resolve"),
                ),
            ),
            listOf(gsFingerprint),
        )
        assertFalse(result.matches)
        val report = result.report()
        assertTrue(report.contains("ResolutionStart"))
        assertTrue(report.contains("ResolutionComplete"))
    }

    @Test
    fun wrongMessageTypeReported() {
        val result = StructuralDiff.compare(listOf(gsFingerprint), listOf(actionsFingerprint))
        assertFalse(result.matches)
        assertTrue(result.report().contains("greMessageType"))
    }

    @Test
    fun wrongUpdateTypeReported() {
        val result = StructuralDiff.compare(
            listOf(gsFingerprint.copy(updateType = "SendHiFi")),
            listOf(gsFingerprint.copy(updateType = "Send")),
        )
        assertFalse(result.matches)
        assertTrue(result.report().contains("updateType"))
    }
}
