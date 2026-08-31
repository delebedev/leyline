package leyline.bridge.handoff

internal fun resolvePromptPolicyDefault(
    request: PromptRequest,
    onResolved: (List<Int>) -> Unit,
): List<Int>? {
    val policyDefault = request.policyDefault() ?: return null
    onResolved(policyDefault.indices)
    return policyDefault.indices
}
