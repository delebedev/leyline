package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.string.shouldContain
import leyline.UnitTag

class StructuralDiffTest :
    FunSpec({

        tags(UnitTag)

        val gsFingerprint = StructuralFingerprint(
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

        val actionsFingerprint = StructuralFingerprint(
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

        test("identicalSequencesMatch") {
            val seq = listOf(gsFingerprint, actionsFingerprint)
            val result = StructuralDiff.compare(seq, seq)
            result.matches.shouldBeTrue()
            result.divergences.shouldBeEmpty()
        }

        test("differentLengthsReported") {
            val result = StructuralDiff.compare(
                listOf(gsFingerprint, actionsFingerprint),
                listOf(gsFingerprint),
            )
            result.matches.shouldBeFalse()
            result.report() shouldContain "length"
        }

        test("missingAnnotationReported") {
            val result = StructuralDiff.compare(
                listOf(
                    gsFingerprint.copy(
                        annotationTypes = listOf("ResolutionComplete", "ResolutionStart", "ZoneTransfer"),
                        annotationCategories = listOf("Resolve"),
                    ),
                ),
                listOf(gsFingerprint),
            )
            result.matches.shouldBeFalse()
            val report = result.report()
            report shouldContain "ResolutionStart"
            report shouldContain "ResolutionComplete"
        }

        test("wrongMessageTypeReported") {
            val result = StructuralDiff.compare(listOf(gsFingerprint), listOf(actionsFingerprint))
            result.matches.shouldBeFalse()
            result.report() shouldContain "greMessageType"
        }

        test("wrongUpdateTypeReported") {
            val result = StructuralDiff.compare(
                listOf(gsFingerprint.copy(updateType = "SendHiFi")),
                listOf(gsFingerprint.copy(updateType = "Send")),
            )
            result.matches.shouldBeFalse()
            result.report() shouldContain "updateType"
        }
    })
