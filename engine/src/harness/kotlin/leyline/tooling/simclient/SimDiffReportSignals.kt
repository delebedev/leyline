package leyline.tooling.simclient

internal enum class SimDiffRowHealth(
    val label: String,
) {
    Natural("natural"),
    MaxTurns("max-turns"),
    Issue("issue"),
    ;

    val isIssue: Boolean get() = this == Issue

    companion object {
        fun from(
            completionReason: String,
            exceptionMessage: String?,
            errorsByType: Map<String, Int>,
        ): SimDiffRowHealth =
            when {
                exceptionMessage != null || errorsByType.isNotEmpty() -> Issue
                completionReason == "natural" -> Natural
                completionReason == "max-turns" -> MaxTurns
                else -> Issue
            }
    }
}

internal data class SimDiffCallbackDisposition(
    val status: String,
    val note: String,
)

internal fun simDiffCallbackDisposition(
    callback: String,
    mapped: Boolean,
): SimDiffCallbackDisposition? =
    when (callback) {
        "chooseColor" ->
            SimDiffCallbackDisposition(
                status = if (mapped) "mapped" else "unmapped",
                note = "mana production uses ActionsAvailableReq/Activate_Mana; static color choices use SelectNReq",
            )
        "orderSimultaneousSa" ->
            SimDiffCallbackDisposition(
                status = "unmapped",
                note = "needs ordering prompt route evidence before mapping",
            )
        "chooseCounterType" ->
            SimDiffCallbackDisposition(
                status = "unmapped",
                note = "needs counter-type selection prompt route evidence before mapping",
            )
        else -> if (mapped) null else SimDiffCallbackDisposition("unmapped", "no report mapping yet")
    }

internal fun advisorGapCategory(
    prompt: String,
    sample: String?,
): String =
    when (prompt) {
        "ActionsAvailableReq" -> actionAdvisorCategory(sample)
        "SelectTargetsReq" -> "target-choice"
        "DeclareAttackersReq" -> "attack-choice"
        "DeclareBlockersReq" -> "block-choice"
        else -> "choice"
    }

internal fun advisorSampleSummary(sample: String?): String? {
    if (sample == null) return null
    val greedy = sample.decisionSegment("greedy")?.humanDecision() ?: "unknown"
    val advisor = sample.decisionSegment("advisor")?.humanDecision() ?: "unknown"
    val promptOptions = sample.promptOptionCount()?.let { "; promptOptions=$it" }.orEmpty()
    return "greedy=$greedy; advisor=$advisor$promptOptions"
}

private fun actionAdvisorCategory(sample: String?): String {
    val greedy = sample?.decisionSegment("greedy")?.parsePerformAction()
    val advisor = sample?.decisionSegment("advisor")?.parsePerformAction()
    if (greedy == null || advisor == null) return "action-choice"
    if (greedy.actionName.isManaAction() || advisor.actionName.isManaAction()) return "mana-vs-action"
    if (greedy.actionName.isPassAction() || advisor.actionName.isPassAction()) return "play-vs-pass"
    if (greedy.actionName.isLandPlay() && advisor.actionName.isLandPlay()) return "land/play-sequencing"
    if (greedy.actionName.isCastAction() && advisor.actionName.isCastAction()) return "spell-choice"
    if (greedy.actionName == advisor.actionName && greedy.grp != advisor.grp) return "${greedy.actionName.toBucketName()}-choice"
    return "action-choice"
}

private data class ParsedPerformAction(
    val actionName: String,
    val iid: String,
    val grp: String,
    val ability: String,
    val alt: String,
)

private fun String.decisionSegment(name: String): String? =
    Regex("(?:^|;)${Regex.escape(name)}=([^;]*)")
        .find(this)
        ?.groupValues
        ?.get(1)

private fun String.promptOptionCount(): Int? =
    Regex("(?:^|;)prompt=(.*)$")
        .find(this)
        ?.groupValues
        ?.get(1)
        ?.takeIf { it.contains('|') }
        ?.split('|')
        ?.size

private fun String.humanDecision(): String {
    parsePerformAction()?.let { action ->
        val ability =
            action.ability
                .takeUnless { it == "0" }
                ?.let { " ability=$it" }
                .orEmpty()
        val alt =
            action.alt
                .takeUnless { it == "0" }
                ?.let { " alt=$it" }
                .orEmpty()
        return "${action.actionName} iid=${action.iid} grp=${action.grp}$ability$alt"
    }
    if (startsWith("select-targets:")) {
        return "target ids ${removePrefix("select-targets:").ifBlank { "none" }}"
    }
    if (startsWith("declare-attackers:")) {
        return "attackers ${removePrefix("declare-attackers:").ifBlank { "none" }}"
    }
    return this
}

private fun String.parsePerformAction(): ParsedPerformAction? {
    val match = Regex("perform:([^:]+):iid=([^:]+):grp=([^:]+):ability=([^:]+):alt=([^:]+)").find(this) ?: return null
    return ParsedPerformAction(
        actionName = match.groupValues[1],
        iid = match.groupValues[2],
        grp = match.groupValues[3],
        ability = match.groupValues[4],
        alt = match.groupValues[5],
    )
}

private fun String.isManaAction(): Boolean = contains("Mana", ignoreCase = true)

private fun String.isPassAction(): Boolean = equals("Pass", ignoreCase = true)

private fun String.isLandPlay(): Boolean = startsWith("Play", ignoreCase = true)

private fun String.isCastAction(): Boolean = startsWith("Cast", ignoreCase = true)

private fun String.toBucketName(): String =
    replace(Regex("[^A-Za-z0-9]+"), "-")
        .trim('-')
        .lowercase()
