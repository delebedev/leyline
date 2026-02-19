package forge.nexus.conformance

import java.io.File

/**
 * Classifies a flat [StructuralFingerprint] sequence into a structured game timeline.
 *
 * Walks the list once, labeling each message or message cluster as a known pattern
 * (GAME_START, PHASE_TRANSITION, PLAY_LAND, CAST_CREATURE, etc.) or MISC.
 * Optionally checks segments against golden files for conformance.
 */
class GameFlowAnalyzer(
    private val fingerprints: List<StructuralFingerprint>,
    private val goldensDir: File? = null,
) {
    /** Segment types recognized by the analyzer. */
    enum class SegmentType {
        PRE_GAME,
        GAME_START,
        NEW_TURN,
        PHASE_TRANSITION,
        PLAY_LAND_PLAYER,
        PLAY_LAND_AI,
        CAST_CREATURE_AI,
        CAST_SPELL_PLAYER,
        TARGETED_SPELL,
        DRAW,
        COMBAT,
        GAME_END,
        MISC,
    }

    /** A labeled segment of the fingerprint sequence. */
    data class Segment(
        val type: SegmentType,
        val startIndex: Int,
        val endIndex: Int, // inclusive
        val label: String,
        val children: List<Segment> = emptyList(),
    ) {
        val size: Int get() = endIndex - startIndex + 1
    }

    /** A turn grouping segments under a turn header. */
    data class Turn(
        val turnNumber: Int,
        val isPlayer: Boolean,
        val startIndex: Int,
        val endIndex: Int,
        val segments: List<Segment>,
    )

    /** Top-level analysis result. */
    data class Analysis(
        val preGame: Segment?,
        val gameStarts: List<Segment>,
        val turns: List<Turn>,
        val gameEnds: List<Segment>,
        val allSegments: List<Segment>,
    )

    // Golden file cache (loaded lazily)
    private val goldenCache = mutableMapOf<String, List<StructuralFingerprint>?>()

    fun analyze(): Analysis {
        val segments = classifyAll()
        return groupIntoTimeline(segments)
    }

    /**
     * Produce human-readable timeline output.
     */
    fun report(): String {
        val analysis = analyze()
        return buildString {
            // Pre-game
            if (analysis.preGame != null) {
                val seg = analysis.preGame
                appendLine("=== PRE-GAME [${seg.startIndex}-${seg.endIndex}] ===")
                val types = fingerprints.subList(seg.startIndex, seg.endIndex + 1)
                    .map { it.greMessageType }
                    .groupingBy { it }.eachCount()
                    .entries.joinToString(", ") { (k, v) -> if (v > 1) "$k x$v" else k }
                appendLine("  $types")
                appendLine()
            }

            // Game starts + turns, interleaved in order
            val events = mutableListOf<Pair<Int, () -> Unit>>()

            for (gs in analysis.gameStarts) {
                events.add(
                    gs.startIndex to {
                        val golden = checkGolden("game-start", gs)
                        appendLine("=== GAME START [${gs.startIndex}-${gs.endIndex}] (${gs.size} msgs)$golden ===")
                        appendLine()
                    },
                )
            }

            for (turn in analysis.turns) {
                events.add(
                    turn.startIndex to {
                        val who = if (turn.isPlayer) "player" else "AI"
                        appendLine("=== Turn ${turn.turnNumber} ($who, indices ${turn.startIndex}-${turn.endIndex}) ===")
                        printTurnSegments(turn.segments, this)
                        appendLine()
                    },
                )
            }

            for (ge in analysis.gameEnds) {
                events.add(
                    ge.startIndex to {
                        appendLine("=== GAME END [${ge.startIndex}-${ge.endIndex}] ===")
                        appendLine()
                    },
                )
            }

            // Sort by start index and emit
            events.sortBy { it.first }
            for ((_, emit) in events) {
                emit()
            }
        }
    }

    private fun printTurnSegments(segments: List<Segment>, sb: StringBuilder) {
        // Collapse consecutive PHASE_TRANSITION segments
        var i = 0
        while (i < segments.size) {
            val seg = segments[i]
            if (seg.type == SegmentType.PHASE_TRANSITION) {
                // Count consecutive phase transitions
                var count = 1
                var end = seg.endIndex
                var j = i + 1
                while (j < segments.size && segments[j].type == SegmentType.PHASE_TRANSITION) {
                    count++
                    end = segments[j].endIndex
                    j++
                }
                val golden = if (count == 1) {
                    checkGolden("phase-transition", seg)
                } else {
                    checkGolden("phase-transition", segments[i])
                }
                sb.appendLine(
                    "  PHASE_TRANSITION x$count [${seg.startIndex}-$end]$golden",
                )
                i = j
            } else {
                val goldenKey = goldenKeyFor(seg.type)
                val golden = if (goldenKey != null) checkGolden(goldenKey, seg) else ""
                val newFlag = if (goldenKey == null &&
                    seg.type != SegmentType.MISC &&
                    seg.type != SegmentType.NEW_TURN
                ) {
                    " <- NEW (no golden)"
                } else {
                    ""
                }
                sb.appendLine("  ${seg.label} [${seg.startIndex}-${seg.endIndex}]$golden$newFlag")
                for (child in seg.children) {
                    sb.appendLine("    ${child.label} [${child.startIndex}-${child.endIndex}]")
                }
                i++
            }
        }
    }

    private fun goldenKeyFor(type: SegmentType): String? = when (type) {
        SegmentType.PLAY_LAND_PLAYER -> "play-land"
        SegmentType.PLAY_LAND_AI -> null // AI land uses different shape
        SegmentType.CAST_CREATURE_AI -> "cast-creature"
        SegmentType.CAST_SPELL_PLAYER -> null
        else -> null
    }

    private fun checkGolden(name: String, seg: Segment): String {
        if (goldensDir == null) return ""
        val golden = loadGolden(name) ?: return " <- NEW (no golden)"
        val actual = fingerprints.subList(seg.startIndex, seg.endIndex + 1)
        val result = StructuralDiff.compare(golden, actual)
        return if (result.matches) {
            " -- matches golden"
        } else {
            val divergenceCount = result.divergences.size +
                (if (result.lengthMismatch != null) 1 else 0)
            " -- $divergenceCount divergences vs golden"
        }
    }

    private fun loadGolden(name: String): List<StructuralFingerprint>? = goldenCache.getOrPut(name) {
        val file = File(goldensDir, "$name.json")
        if (file.exists()) GoldenSequence.fromFile(file) else null
    }

    // ---- Classification engine ----

    private fun classifyAll(): List<Segment> {
        val segments = mutableListOf<Segment>()
        var i = 0

        while (i < fingerprints.size) {
            val fp = fingerprints[i]

            // Game start: first PhaseOrStepModified x2 cluster after mulligan
            if (isGameStartAt(i)) {
                segments.add(Segment(SegmentType.GAME_START, i, i + 4, "GAME_START"))
                i += 5
                continue
            }

            // New turn
            if (hasAnnotation(fp, "NewTurnStarted")) {
                val end = if (i + 1 < fingerprints.size && isEmptyMarker(fingerprints[i + 1])) {
                    i + 1
                } else {
                    i
                }
                segments.add(Segment(SegmentType.NEW_TURN, i, end, "NEW_TURN"))
                i = end + 1
                continue
            }

            // Draw step (comes with PhaseOrStepModified + Draw category)
            if (fp.annotationCategories.contains("Draw")) {
                val end = if (i + 1 < fingerprints.size && isEmptyMarker(fingerprints[i + 1])) {
                    i + 1
                } else {
                    i
                }
                segments.add(Segment(SegmentType.DRAW, i, end, "DRAW"))
                i = end + 1
                continue
            }

            // Targeted spell: SelectTargetsReq or PlayerSelectingTargets + CastSpell
            if (isTargetedSpellStart(i)) {
                val (seg, nextI) = consumeTargetedSpell(i)
                segments.add(seg)
                i = nextI
                continue
            }

            // Cast creature/spell (AI): CastSpell category + SendHiFi, 4-msg group
            if (isCastCreatureAI(i)) {
                val (seg, nextI) = consumeCastCreatureAI(i)
                segments.add(seg)
                i = nextI
                continue
            }

            // Cast spell (player): CastSpell category + SendAndRecord
            if (isCastSpellPlayer(i)) {
                val (seg, nextI) = consumeCastSpellPlayer(i)
                segments.add(seg)
                i = nextI
                continue
            }

            // Play land with SendAndRecord — dual-message = AI, single = player
            if (isPlayLandSendAndRecord(fp)) {
                if (i + 1 < fingerprints.size && isPlayLandSendAndRecord(fingerprints[i + 1])) {
                    // Dual SendAndRecord (both seats notified) = AI land play
                    val end = if (i + 2 < fingerprints.size &&
                        fingerprints[i + 2].greMessageType == "ActionsAvailableReq"
                    ) {
                        i + 2
                    } else {
                        i + 1
                    }
                    segments.add(Segment(SegmentType.PLAY_LAND_AI, i, end, "PLAY_LAND (AI)"))
                    i = end + 1
                    continue
                }
                // Single SendAndRecord = player land play
                val end = if (i + 1 < fingerprints.size &&
                    fingerprints[i + 1].greMessageType == "ActionsAvailableReq"
                ) {
                    i + 1
                } else {
                    i
                }
                segments.add(Segment(SegmentType.PLAY_LAND_PLAYER, i, end, "PLAY_LAND (player)"))
                i = end + 1
                continue
            }

            // Play land (AI): PlayLand category + SendHiFi
            if (isPlayLandAI(fp)) {
                val end = if (i + 1 < fingerprints.size && isEmptyMarker(fingerprints[i + 1])) {
                    i + 1
                } else {
                    i
                }
                segments.add(Segment(SegmentType.PLAY_LAND_AI, i, end, "PLAY_LAND (AI)"))
                i = end + 1
                continue
            }

            // Phase transition: PhaseOrStepModified on GS Diff + empty marker pair
            if (isPhaseTransition(i)) {
                val end = if (i + 1 < fingerprints.size && isEmptyMarker(fingerprints[i + 1])) {
                    i + 1
                } else {
                    i
                }
                segments.add(Segment(SegmentType.PHASE_TRANSITION, i, end, "PHASE_TRANSITION"))
                i = end + 1
                continue
            }

            // Combat: DeclareAttackersReq, DeclareBlockersReq, SubmitAttackersResp, SubmitBlockersResp
            if (isCombatStart(fp)) {
                val (seg, nextI) = consumeCombat(i)
                segments.add(seg)
                i = nextI
                continue
            }

            // Game end: IntermissionReq
            if (fp.greMessageType == "IntermissionReq") {
                // Collect the preceding empty GS Diffs as part of game end
                var start = i
                // Look backward for empty SendAndRecord diffs
                while (start > 0 && segments.isNotEmpty()) {
                    val prev = segments.last()
                    if (prev.type == SegmentType.MISC && prev.endIndex == start - 1) {
                        val prevFp = fingerprints[prev.startIndex]
                        if (prevFp.greMessageType == "GameStateMessage" &&
                            prevFp.updateType == "SendAndRecord" &&
                            prevFp.annotationTypes.isEmpty()
                        ) {
                            start = prev.startIndex
                            segments.removeLast()
                        } else {
                            break
                        }
                    } else {
                        break
                    }
                }
                segments.add(Segment(SegmentType.GAME_END, start, i, "GAME_END"))
                i++
                continue
            }

            // Noise / misc
            if (isNoise(fp)) {
                segments.add(Segment(SegmentType.MISC, i, i, fp.greMessageType))
                i++
                continue
            }

            // Unclassified: label with message type
            segments.add(Segment(SegmentType.MISC, i, i, fp.greMessageType))
            i++
        }

        return segments
    }

    private fun groupIntoTimeline(segments: List<Segment>): Analysis {
        var preGame: Segment? = null
        val gameStarts = mutableListOf<Segment>()
        val turns = mutableListOf<Turn>()
        val gameEnds = mutableListOf<Segment>()

        // Find pre-game region: everything before first GAME_START
        val firstGameStart = segments.indexOfFirst { it.type == SegmentType.GAME_START }
        if (firstGameStart > 0) {
            val start = segments[0].startIndex
            val end = segments[firstGameStart - 1].endIndex
            preGame = Segment(SegmentType.PRE_GAME, start, end, "PRE_GAME")
        }

        // Collect game starts and game ends
        for (seg in segments) {
            when (seg.type) {
                SegmentType.GAME_START -> gameStarts.add(seg)
                SegmentType.GAME_END -> gameEnds.add(seg)
                else -> {}
            }
        }

        // Group by turns
        var turnNumber = 0
        var currentTurnSegments = mutableListOf<Segment>()
        var turnStartIndex = -1
        var isPlayerTurn = false
        var inGame = false

        for (seg in segments) {
            // Skip pre-game content and game structure markers
            if (!inGame && seg.type != SegmentType.GAME_START) continue
            if (seg.type == SegmentType.GAME_START) {
                // Flush any accumulated turn
                if (currentTurnSegments.isNotEmpty()) {
                    turns.add(
                        Turn(
                            turnNumber,
                            isPlayerTurn,
                            turnStartIndex,
                            currentTurnSegments.last().endIndex,
                            currentTurnSegments.toList(),
                        ),
                    )
                    currentTurnSegments = mutableListOf()
                }
                inGame = true
                turnNumber = 0
                continue
            }
            if (seg.type == SegmentType.GAME_END) {
                // Flush turn
                if (currentTurnSegments.isNotEmpty()) {
                    turns.add(
                        Turn(
                            turnNumber,
                            isPlayerTurn,
                            turnStartIndex,
                            currentTurnSegments.last().endIndex,
                            currentTurnSegments.toList(),
                        ),
                    )
                    currentTurnSegments = mutableListOf()
                }
                inGame = false
                continue
            }
            if (!inGame) continue

            // Noise between turns
            if (seg.type == SegmentType.MISC && isNoise(fingerprints[seg.startIndex])) {
                // Skip noise but keep it if we're in a turn
                if (currentTurnSegments.isNotEmpty()) {
                    currentTurnSegments.add(seg)
                }
                continue
            }

            if (seg.type == SegmentType.NEW_TURN) {
                // Flush previous turn
                if (currentTurnSegments.isNotEmpty()) {
                    turns.add(
                        Turn(
                            turnNumber,
                            isPlayerTurn,
                            turnStartIndex,
                            currentTurnSegments.last().endIndex,
                            currentTurnSegments.toList(),
                        ),
                    )
                    currentTurnSegments = mutableListOf()
                }
                turnNumber++
                turnStartIndex = seg.startIndex
                // Determine if player or AI turn: check if ActionsAvailableReq follows
                // before the next NEW_TURN
                isPlayerTurn = hasPlayerPriorityBeforeNextTurn(seg.endIndex + 1)
                currentTurnSegments.add(seg)
                continue
            }

            // First content after game start (before any NEW_TURN) — turn 1
            if (turnNumber == 0) {
                turnNumber = 1
                turnStartIndex = seg.startIndex
                isPlayerTurn = hasPlayerPriorityBeforeNextTurn(seg.startIndex)
            }

            currentTurnSegments.add(seg)
        }

        // Flush final turn
        if (currentTurnSegments.isNotEmpty()) {
            turns.add(
                Turn(
                    turnNumber,
                    isPlayerTurn,
                    turnStartIndex,
                    currentTurnSegments.last().endIndex,
                    currentTurnSegments.toList(),
                ),
            )
        }

        return Analysis(preGame, gameStarts, turns, gameEnds, segments)
    }

    /** Check if ActionsAvailableReq appears before the next NewTurnStarted. */
    private fun hasPlayerPriorityBeforeNextTurn(fromIndex: Int): Boolean {
        for (k in fromIndex until fingerprints.size) {
            val fp = fingerprints[k]
            if (hasAnnotation(fp, "NewTurnStarted")) return false
            if (fp.greMessageType == "ActionsAvailableReq") return true
            // Game boundary also stops search
            if (fp.greMessageType == "IntermissionReq") return false
        }
        return false
    }

    // ---- Pattern matchers ----

    private fun isGameStartAt(i: Int): Boolean {
        if (i + 4 >= fingerprints.size) return false
        val fp = fingerprints[i]
        // Must be a GS Diff with PhaseOrStepModified x2 at SendHiFi
        if (fp.greMessageType != "GameStateMessage") return false
        if (fp.gsType != "Diff") return false
        if (fp.updateType != "SendHiFi") return false
        val phaseCount = fp.annotationTypes.count { it == "PhaseOrStepModified" }
        if (phaseCount < 2) return false
        // Must follow a mulligan region — check that a MulliganReq exists within the last 10 msgs
        val hasMulligan = (maxOf(0, i - 15) until i).any {
            fingerprints[it].greMessageType == "MulliganReq"
        }
        if (!hasMulligan) return false
        // Check the 5-msg pattern shape
        val fp1 = fingerprints[i + 1]
        if (fp1.greMessageType != "GameStateMessage" || fp1.gsType != "Diff") return false
        val fp2 = fingerprints[i + 2]
        if (fp2.greMessageType != "GameStateMessage" || fp2.gsType != "Diff") return false
        if (fp2.updateType != "SendAndRecord") return false
        val fp3 = fingerprints[i + 3]
        if (fp3.greMessageType != "PromptReq") return false
        val fp4 = fingerprints[i + 4]
        if (fp4.greMessageType != "ActionsAvailableReq") return false
        return true
    }

    private fun isPhaseTransition(i: Int): Boolean {
        val fp = fingerprints[i]
        if (fp.greMessageType != "GameStateMessage") return false
        if (fp.gsType != "Diff") return false
        if (!fp.annotationTypes.contains("PhaseOrStepModified")) return false
        // Must not be a NewTurnStarted (those also have PhaseOrStepModified)
        if (fp.annotationTypes.contains("NewTurnStarted")) return false
        // Must not have other interesting annotations (Draw, PlayLand, etc.)
        if (fp.annotationCategories.isNotEmpty()) return false
        // Only PhaseOrStepModified annotations (no other types besides maybe TappedUntapped)
        val nonPhase = fp.annotationTypes.filter {
            it != "PhaseOrStepModified" && it != "TappedUntappedPermanent"
        }
        if (nonPhase.isNotEmpty()) return false
        return true
    }

    private fun isEmptyMarker(fp: StructuralFingerprint): Boolean {
        if (fp.greMessageType != "GameStateMessage") return false
        if (fp.gsType != "Diff") return false
        return fp.annotationTypes.isEmpty() && fp.annotationCategories.isEmpty()
    }

    private fun isPlayLandSendAndRecord(fp: StructuralFingerprint): Boolean = fp.annotationCategories.contains("PlayLand") &&
        fp.updateType == "SendAndRecord"

    private fun isPlayLandAI(fp: StructuralFingerprint): Boolean = fp.annotationCategories.contains("PlayLand") &&
        fp.updateType == "SendHiFi"

    private fun isCastCreatureAI(i: Int): Boolean {
        val fp = fingerprints[i]
        if (!fp.annotationCategories.contains("CastSpell")) return false
        if (fp.updateType != "SendHiFi") return false
        // Must have 4-msg pattern: cast + empty + resolve + empty
        if (i + 3 >= fingerprints.size) return false
        if (!isEmptyMarker(fingerprints[i + 1])) return false
        val resolve = fingerprints[i + 2]
        if (!resolve.annotationCategories.contains("Resolve")) return false
        if (!isEmptyMarker(fingerprints[i + 3])) return false
        return true
    }

    private fun consumeCastCreatureAI(i: Int): Pair<Segment, Int> = Segment(SegmentType.CAST_CREATURE_AI, i, i + 3, "CAST_CREATURE (AI)") to (i + 4)

    private fun isCastSpellPlayer(i: Int): Boolean {
        val fp = fingerprints[i]
        if (!fp.annotationCategories.contains("CastSpell")) return false
        if (fp.updateType != "SendAndRecord") return false
        return true
    }

    private fun consumeCastSpellPlayer(i: Int): Pair<Segment, Int> {
        // Player cast: find the resolution or next ActionsAvailableReq
        var end = i
        for (k in i + 1 until minOf(fingerprints.size, i + 20)) {
            val fp = fingerprints[k]
            if (fp.greMessageType == "ActionsAvailableReq") {
                end = k
                break
            }
            if (hasAnnotation(fp, "NewTurnStarted")) {
                end = k - 1
                break
            }
            end = k
        }
        return Segment(SegmentType.CAST_SPELL_PLAYER, i, end, "CAST_SPELL (player)") to (end + 1)
    }

    private fun isTargetedSpellStart(i: Int): Boolean {
        val fp = fingerprints[i]
        // CastSpell with Send updateType (targeting phase)
        if (fp.annotationCategories.contains("CastSpell") && fp.updateType == "Send") return true
        // SelectTargetsReq as standalone
        if (fp.greMessageType == "SelectTargetsReq") return true
        // PlayerSelectingTargets annotation
        if (fp.annotationTypes.contains("PlayerSelectingTargets") &&
            fp.updateType == "Send"
        ) {
            return true
        }
        return false
    }

    private fun consumeTargetedSpell(i: Int): Pair<Segment, Int> {
        val children = mutableListOf<Segment>()
        var end = i
        var phase = "targeting"
        var phaseStart = i

        for (k in i until minOf(fingerprints.size, i + 40)) {
            val fp = fingerprints[k]

            // Resolution complete marks the end
            if (fp.annotationTypes.contains("ResolutionComplete") && k > i + 2) {
                // Include trailing empty marker if present
                end = k
                if (k + 1 < fingerprints.size && isEmptyMarker(fingerprints[k + 1])) {
                    end = k + 1
                }
                children.add(Segment(SegmentType.MISC, phaseStart, end, "resolution"))
                end = end
                break
            }

            // Transition from targeting to cast
            if (phase == "targeting" && fp.annotationTypes.contains("PlayerSubmittedTargets")) {
                children.add(Segment(SegmentType.MISC, phaseStart, k - 1, "targeting"))
                phase = "cast"
                phaseStart = k
            }

            // Transition from cast to resolution
            if (phase == "cast" &&
                (
                    fp.annotationTypes.contains("ResolutionStart") ||
                        fp.annotationTypes.contains("RevealedCardCreated")
                    )
            ) {
                if (phaseStart < k) {
                    children.add(Segment(SegmentType.MISC, phaseStart, k - 1, "cast"))
                }
                phase = "resolution"
                phaseStart = k
            }

            end = k

            // Safety: NewTurnStarted or game boundary
            if (hasAnnotation(fp, "NewTurnStarted") && k > i) break
            if (fp.greMessageType == "IntermissionReq") break
        }

        // Flush remaining phase
        if (children.isEmpty() || children.last().endIndex < end) {
            children.add(Segment(SegmentType.MISC, phaseStart, end, phase))
        }

        return Segment(
            SegmentType.TARGETED_SPELL,
            i,
            end,
            "TARGETED_SPELL",
            children,
        ) to (end + 1)
    }

    private fun isCombatStart(fp: StructuralFingerprint): Boolean = fp.greMessageType == "DeclareAttackersReq"

    private fun consumeCombat(i: Int): Pair<Segment, Int> {
        var end = i
        for (k in i until minOf(fingerprints.size, i + 60)) {
            val fp = fingerprints[k]
            end = k
            // Combat ends at next phase transition, new turn, or ActionsAvailableReq after blockers
            if (k > i &&
                (
                    hasAnnotation(fp, "NewTurnStarted") ||
                        fp.greMessageType == "IntermissionReq"
                    )
            ) {
                end = k - 1
                break
            }
            // PhaseOrStepModified after SubmitBlockersResp or SubmitAttackersResp signals end
            if (k > i + 2 &&
                fp.annotationTypes.contains("PhaseOrStepModified") &&
                !fp.annotationTypes.contains("DamageDealt")
            ) {
                // Check if we've had attackers/blockers resolution
                val hadSubmit = (i until k).any {
                    fingerprints[it].greMessageType == "SubmitAttackersResp" ||
                        fingerprints[it].greMessageType == "SubmitBlockersResp"
                }
                if (hadSubmit) {
                    end = k - 1
                    break
                }
            }
        }
        return Segment(SegmentType.COMBAT, i, end, "COMBAT") to (end + 1)
    }

    @Suppress("BooleanMethodIsNegation")
    private fun isNoise(fp: StructuralFingerprint): Boolean = fp.greMessageType in NOISE_TYPES

    private fun hasAnnotation(fp: StructuralFingerprint, type: String): Boolean = fp.annotationTypes.contains(type)

    companion object {
        private val NOISE_TYPES = setOf(
            "SetSettingsResp",
            "Uimessage",
            "ConnectResp",
            "DieRollResultsResp",
            "ChooseStartingPlayerReq",
            "MulliganReq",
            "PromptReq",
            "EdictalMessage",
            "SubmitTargetsResp",
        )
    }
}
