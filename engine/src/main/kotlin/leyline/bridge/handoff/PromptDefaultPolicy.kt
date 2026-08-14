package leyline.bridge.handoff

import org.slf4j.Logger

internal fun resolvePromptPolicyDefault(
    request: PromptRequest,
    log: Logger,
    onResolved: (List<Int>) -> Unit,
): List<Int>? {
    val policyDefault = request.policyDefault() ?: return null
    if (policyDefault.warnAmbiguousGeneric) {
        log.warn(
            "Defaulting unclassified non-interactive prompt [{}] \"{}\" options={} default={}",
            request.promptType,
            request.message,
            request.options.size,
            request.defaultIndex,
        )
    }
    onResolved(policyDefault.indices)
    return policyDefault.indices
}
