package leyline.tooling.headless

/** Controls whether [MatchFlowHarness] consumes engine prompts automatically. */
enum class HeadlessResponseMode {
    /** Preserve legacy test convenience: auto-answer simple engine prompts during drain. */
    AutoForTests,

    /** Surface prompts to the caller first so policy/telemetry can own responses. */
    PolicyVisible,

    /** Never auto-answer prompts; caller must respond explicitly. */
    Manual,
}
