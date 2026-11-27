package leyline.tooling.simclient

import leyline.bridge.OfferDiffDiagnostics
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType

const val STATS_SCHEMA_VERSION = 4

data class GameStats(
    val turn: Int,
    val gameOver: Boolean,
    val winnerSeat: Int? = null,
    val loserSeat: Int? = null,
    val finalLifeBySeat: Map<String, Int> = emptyMap(),
    val finalStatusBySeat: Map<String, String> = emptyMap(),
    val iterations: Int,
    val totalMessages: Int,
    val promptHistogram: Map<GREMessageType, Int>,
    val hitIterCap: Boolean,
    val durationMs: Long = 0,
    val aiConsulted: Int = 0,
    val aiChose: Int = 0,
    val aiConsultedByPrompt: Map<String, Int> = emptyMap(),
    val aiChoseByPrompt: Map<String, Int> = emptyMap(),
    val aiTotalMs: Long = 0,
    val aiTotalMsByPrompt: Map<String, Long> = emptyMap(),
    val aiMaxMsByPrompt: Map<String, Long> = emptyMap(),
    val targetChoiceCounts: Map<String, Int> = emptyMap(),
    val targetChoiceSamples: Map<String, String> = emptyMap(),
    val advisorUnavailableByReason: Map<String, Int> = emptyMap(),
    val snapshotFidelityGrades: Map<String, Int> = emptyMap(),
    val snapshotImportFindings: Map<String, Int> = emptyMap(),
    val snapshotDecisionSources: Map<String, Int> = emptyMap(),
    val snapshotSemanticAgreement: Map<String, Int> = emptyMap(),
    val snapshotSemanticMismatchSamples: Map<String, String> = emptyMap(),
    /** Forge AI searches that ran past their time limit, keyed by decision. */
    val aiTimeoutsByDecision: Map<String, Int> = emptyMap(),
    val warnsByLogger: Map<String, Int> = emptyMap(),
    val errorsByType: Map<String, Int> = emptyMap(),
    val logErrorSamples: List<String> = emptyList(),
    val validationViolationsByCheck: Map<String, Int> = emptyMap(),
    val validationViolations: List<String> = emptyList(),
    val exceptionMessage: String? = null,
    val exceptionStackTop: String? = null,
    val stalledPrompt: String? = null,
    val stalledFingerprint: String? = null,
    val completionReason: String = "unknown",
    val cleanupConcede: Boolean = false,
    val promptRetiredByReason: Map<String, Int> = emptyMap(),
    val decisionOutcomes: Map<String, Int> = emptyMap(),
    val actionAttemptsByType: Map<String, Int> = emptyMap(),
    val noPendingByDecision: Map<String, Int> = emptyMap(),
    val skippedAlreadyTried: Int = 0,
    val connectMs: Long = 0,
    val stepTotalMs: Long = 0,
    val stepMaxMs: Long = 0,
    val flushTotalMs: Long = 0,
    val flushMaxMs: Long = 0,
    val autoPassTotalMs: Long = 0,
    val autoPassMaxMs: Long = 0,
    val policyTotalMsByPrompt: Map<String, Long> = emptyMap(),
    val policyMaxMsByPrompt: Map<String, Long> = emptyMap(),
    val submitTotalMsByDecision: Map<String, Long> = emptyMap(),
    val submitMaxMsByDecision: Map<String, Long> = emptyMap(),
    val promptRequestsByKind: Map<String, Int> = emptyMap(),
    val promptRequestSamplesByKind: Map<String, String> = emptyMap(),
    val promptRouteFindings: List<PromptRouteFinding> = emptyList(),
    val unnamedDefaultAnswers: List<UnnamedDefaultAnswer> = emptyList(),
    val automaticAnswersByRule: Map<String, Int> = emptyMap(),
    val simFindings: List<SimClientFinding> = emptyList(),
    val promptProgressSamples: List<PromptProgressSample> = emptyList(),
    /** Active offers built on uncertain affordability, by card. */
    val offeredWhileUnsureByCard: Map<String, Int> = emptyMap(),
    /** Offered actions whose mana payment failed, by card and offering verdict. */
    val offeredThenPaymentFailedByCard: Map<String, Int> = emptyMap(),
    /** Ability identity lookups that resolved, keyed `site/source`. */
    val abilityLookupsBySource: Map<String, Int> = emptyMap(),
    /** Ability identity lookups that found no row, keyed `site/reason`. */
    val abilityLookupMisses: Map<String, Int> = emptyMap(),
    /** Resolved ability rows that disagree with their Forge ability, keyed `site/card: disagreement`. */
    val abilityRowMismatches: Map<String, Int> = emptyMap(),
    /** Offered actions the client could not tell apart from an earlier offer, keyed by action shape. */
    val ambiguousActionCatalogs: Map<String, Int> = emptyMap(),
    /** Differences between published priority windows and Forge's ability enumeration. */
    val offerDiff: OfferDiffDiagnostics.Snapshot = OfferDiffDiagnostics.Snapshot.Empty,
)

data class PromptRouteFinding(
    val bucket: String,
    val routeKey: String,
    val enginePromptType: String,
    val semantic: String,
    val expectedGreType: String,
    val expectedCount: Int,
    val emittedCount: Int,
    val outcomeCounts: Map<String, Int>,
    val sampleMessage: String,
)

/** Seat-1 decisions with two or more options answered by policy without a named rule. */
data class UnnamedDefaultAnswer(
    val askedBy: String,
    val promptType: String,
    val semantic: String,
    val count: Int,
    val sampleMessage: String,
    val sampleOptions: List<String>,
)

data class SimClientFinding(
    val kind: String,
    val key: String,
    val count: Int,
    val sample: String,
)

data class PromptProgressSample(
    val promptType: String,
    val decisionKind: String,
    val submitResult: String,
    val promptMsgId: Int,
    val promptGameStateId: Int,
    val beforeMsgId: Int,
    val beforeGameStateId: Int,
    val afterMsgId: Int,
    val afterGameStateId: Int,
    val beforeMessages: Int,
    val afterMessages: Int,
    val sourceInstanceId: Int = 0,
    val sourceGrpId: Int = 0,
    val abilityGrpId: Int = 0,
    val targetIds: List<Int> = emptyList(),
    val sourceBefore: String = "",
    val sourceAfter: String = "",
)
