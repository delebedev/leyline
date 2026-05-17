package leyline.game.bundle

/** Named invariant checks enforced by [InvariantChecker]. */
enum class InvariantCheck(
    val id: String,
) {
    MsgIdMonotonicity("msgid_monotonicity"),
    MsgIdUnique("msgid_unique"),
    GsIdMonotonicity("gsid_monotonicity"),
    GsIdUnique("gsid_unique"),
    GsIdPrevKnown("gsid_prev_unknown"),
    GsIdNoSelfRef("gsid_self_ref"),
    AnnotationSequentiality("annotation_seq"),
    AnnotationOrdering("annotation_ordering"),
    PhaseFirst("phase_first"),
    ResolutionTransferAfterComplete("resolution_transfer_after_complete"),
    AidAffector("aid_affector"),
    PendingMessageCount("pending_count"),
    ActionInstanceIds("action_iid"),
    ZoneObjects("zone_object"),
    AnnotationReferences("annotation_ref"),
}

/**
 * Selects which protocol invariants are active for a validator run.
 *
 * Full strict validation is the default. Use [only] or [except] in tests that
 * need to keep most checks active while documenting one known blocker.
 */
class InvariantSelection private constructor(
    private val enabled: Set<InvariantCheck>,
    val relaxationReason: String?,
) {
    fun includes(check: InvariantCheck): Boolean = check in enabled

    internal fun includes(checkId: String): Boolean = enabled.any { it.id == checkId }

    fun isEmpty(): Boolean = enabled.isEmpty()

    companion object {
        fun all(): InvariantSelection = InvariantSelection(InvariantCheck.entries.toSet(), relaxationReason = null)

        fun none(because: String): InvariantSelection = InvariantSelection(emptySet(), relaxationReason = because)

        fun only(
            because: String,
            vararg checks: InvariantCheck,
        ): InvariantSelection = InvariantSelection(checks.toSet(), relaxationReason = because)

        fun except(
            because: String,
            vararg checks: InvariantCheck,
        ): InvariantSelection = InvariantSelection(InvariantCheck.entries.toSet() - checks.toSet(), relaxationReason = because)
    }
}
