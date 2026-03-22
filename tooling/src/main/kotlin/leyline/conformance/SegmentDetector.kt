package leyline.conformance

import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage
import wotc.mtgo.gre.external.messaging.Messages.GameStateType

/**
 * Identifies and categorizes protocol segments from raw recording frames.
 *
 * A segment is either:
 * - A GSM Diff containing a ZoneTransfer annotation (mechanic: CastSpell, PlayLand, etc.)
 * - A standalone non-GSM message (SearchReq, DeclareAttackersReq, CastingTimeOptionsReq, etc.)
 */
object SegmentDetector {

    /** Standalone message types that are their own segment. */
    private val STANDALONE_TYPES =
        setOf(
            GREMessageType.SearchReq_695e,
            GREMessageType.DeclareAttackersReq_695e,
            GREMessageType.DeclareBlockersReq_695e,
            GREMessageType.SelectTargetsReq_695e,
            GREMessageType.SelectNreq,
            GREMessageType.CastingTimeOptionsReq_695e,
            GREMessageType.GroupReq_695e,
            GREMessageType.IntermissionReq_695e,
            GREMessageType.ConnectResp_695e,
            GREMessageType.MulliganReq_aa0d,
        )

    private val PROTO_SUFFIX = Regex("_[a-f0-9]{3,4}$")

    /** Detect all segments in a list of recording frames. */
    fun detect(
        frames: List<RecordingFrameLoader.IndexedGREMessage>,
        session: String,
    ): List<Segment> {
        val segments = mutableListOf<Segment>()

        for (frame in frames) {
            val msg = frame.message

            // Standalone messages
            if (msg.type in STANDALONE_TYPES) {
                val category = msg.type.name.replace(PROTO_SUFFIX, "")
                segments.add(Segment(category, msg, session, frame.frameIndex, msg.gameStateId))
                continue
            }

            // GSM Diffs with ZoneTransfer
            if (msg.type == GREMessageType.GameStateMessage_695e && msg.hasGameStateMessage()) {
                val gsm = msg.gameStateMessage
                if (gsm.type != GameStateType.Diff) continue

                val ztCategory = extractZoneTransferCategory(gsm)
                if (ztCategory != null) {
                    segments.add(Segment(ztCategory, msg, session, frame.frameIndex, msg.gameStateId))
                }
            }
        }
        return segments
    }

    /** Group segments by category. */
    fun groupByCategory(segments: List<Segment>): Map<String, List<Segment>> = segments.groupBy { it.category }

    /** Scan all available recording sessions, return all segments. */
    fun scanAll(seat: Int = 0): List<Segment> {
        val recordingsDir = java.io.File("recordings")
        if (!recordingsDir.isDirectory) return emptyList()

        return recordingsDir
            .listFiles()
            ?.filter { it.isDirectory && it.name.matches(Regex("\\d{4}-.*")) }
            ?.sortedBy { it.name }
            ?.flatMap { sessionDir ->
                val session = sessionDir.name
                val frames = RecordingFrameLoader.load(session, seat)
                detect(frames, session)
            }
            ?: emptyList()
    }

    /** Extract ZoneTransfer category from a GSM's annotations. */
    private fun extractZoneTransferCategory(gsm: GameStateMessage): String? {
        for (ann in gsm.annotationsList) {
            val isZoneTransfer = ann.typeList.any { it == AnnotationType.ZoneTransfer_af5a }
            if (!isZoneTransfer) continue

            for (detail in ann.detailsList) {
                if (detail.key == "category" && detail.valueStringList.isNotEmpty()) {
                    return detail.valueStringList[0]
                }
            }
        }
        return null
    }
}
