package leyline.conformance

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateType
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairInfo
import wotc.mtgo.gre.external.messaging.Messages.KeyValuePairValueType
import wotc.mtgo.gre.external.messaging.Messages.SearchReq

class RelationshipValidatorTest :
    FunSpec({

        test("AlwaysPresent holds when annotation type is in every segment") {
            val segments = (1..3).map { castSpellSegment(withObjectIdChanged = true) }
            val pattern = Relationship.AlwaysPresent("CastSpell", "ObjectIdChanged")

            val result = RelationshipValidator.validate(pattern, segments)

            result.status shouldBe ValidationStatus.HOLDS
            result.holds shouldBe 3
            result.total shouldBe 3
        }

        test("AlwaysPresent violated when annotation missing from some segments") {
            val withOIC = castSpellSegment(withObjectIdChanged = true)
            val withoutOIC = castSpellSegment(withObjectIdChanged = false)

            val pattern = Relationship.AlwaysPresent("CastSpell", "ObjectIdChanged")
            val result = RelationshipValidator.validate(pattern, listOf(withOIC, withOIC, withoutOIC))

            result.status shouldBe ValidationStatus.VIOLATED
            result.holds shouldBe 2
            result.total shouldBe 3
            result.exceptions.size shouldBe 1
        }

        test("NonEmpty holds for non-empty list") {
            val gre =
                GREToClientMessage
                    .newBuilder()
                    .setType(GREMessageType.SearchReq_695e)
                    .setSearchReq(SearchReq.newBuilder().addItemsToSearch(100).addItemsToSearch(200))
                    .build()
            val segment = Segment("SearchReq", gre, "test", 1, 10)

            val pattern = Relationship.NonEmpty("SearchReq", "searchReq.itemsToSearch")
            val result = RelationshipValidator.validate(pattern, listOf(segment))

            result.status shouldBe ValidationStatus.HOLDS
        }

        test("NonEmpty violated for empty list") {
            val gre =
                GREToClientMessage
                    .newBuilder()
                    .setType(GREMessageType.SearchReq_695e)
                    .setSearchReq(SearchReq.getDefaultInstance())
                    .build()
            val segment = Segment("SearchReq", gre, "test", 1, 10)

            val pattern = Relationship.NonEmpty("SearchReq", "searchReq.itemsToSearch")
            val result = RelationshipValidator.validate(pattern, listOf(segment))

            result.status shouldBe ValidationStatus.VIOLATED
        }

        test("ValueIn holds when detail value matches") {
            val segment = castSpellSegment(zoneDest = "27")
            val pattern =
                Relationship.ValueIn(
                    "CastSpell",
                    "annotations[ZoneTransfer].details.zone_dest",
                    setOf("27"),
                )
            val result = RelationshipValidator.validate(pattern, listOf(segment))

            result.status shouldBe ValidationStatus.HOLDS
        }

        test("ValueIn violated when value not in set") {
            val segment = castSpellSegment(zoneDest = "28")
            val pattern =
                Relationship.ValueIn(
                    "CastSpell",
                    "annotations[ZoneTransfer].details.zone_dest",
                    setOf("27"),
                )
            val result = RelationshipValidator.validate(pattern, listOf(segment))

            result.status shouldBe ValidationStatus.VIOLATED
        }

        test("validate all patterns for a category against recording") {
            val session = "2026-03-21_22-05-00"
            if (!java.io.File("recordings/$session").exists()) return@test

            val frames = RecordingFrameLoader.load(session, seat = 0)
            val segments = SegmentDetector.detect(frames, session)
            val castSpells = segments.filter { it.category == "CastSpell" }

            val patterns = RelationshipCatalog.forCategory("CastSpell")
            val results = patterns.map { RelationshipValidator.validate(it, castSpells) }

            // All CastSpell patterns should hold against real recording
            for (result in results) {
                if (result.status == ValidationStatus.VIOLATED) {
                    println("VIOLATED: ${result.pattern} — ${result.holds}/${result.total}")
                    for (ex in result.exceptions) println("  ${ex.session} gsId=${ex.gsId}: ${ex.detail}")
                }
            }
        }
    })

// --- Test helpers ---

private var counter = 0

private fun castSpellSegment(
    withObjectIdChanged: Boolean = true,
    zoneDest: String = "27",
): Segment {
    val gsmBuilder = GameStateMessage.newBuilder().setType(GameStateType.Diff)

    val zt =
        AnnotationInfo
            .newBuilder()
            .addType(AnnotationType.ZoneTransfer_af5a)
            .addDetails(
                KeyValuePairInfo
                    .newBuilder()
                    .setKey("category")
                    .setType(KeyValuePairValueType.String)
                    .addValueString("CastSpell"),
            ).addDetails(
                KeyValuePairInfo
                    .newBuilder()
                    .setKey("zone_dest")
                    .setType(KeyValuePairValueType.Uint32)
                    .addValueUint32(zoneDest.toInt()),
            )
    gsmBuilder.addAnnotations(zt)

    if (withObjectIdChanged) {
        gsmBuilder.addAnnotations(
            AnnotationInfo.newBuilder().addType(AnnotationType.ObjectIdChanged),
        )
    }

    val gre =
        GREToClientMessage
            .newBuilder()
            .setType(GREMessageType.GameStateMessage_695e)
            .setGameStateMessage(gsmBuilder)
            .setGameStateId(++counter)
            .build()
    return Segment("CastSpell", gre, "test", counter, counter)
}
