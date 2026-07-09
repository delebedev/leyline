package leyline.tooling.headless

/** Controls whether [MatchFlowHarness] consumes engine prompts automatically. */
enum class HeadlessResponseMode {
    /** Auto-answer simple engine prompts during drain, so tests need not respond to each one. */
    AutoForTests,

    /** Surface prompts to the caller so policy and telemetry own every response. */
    PolicyVisible,
}
