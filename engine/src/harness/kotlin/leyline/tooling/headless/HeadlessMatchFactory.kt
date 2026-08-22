package leyline.tooling.headless

/** Construction entry point for tooling callers. Runtime wiring stays in this package. */
internal object HeadlessMatchFactory {
    fun create(
        spec: MatchSpec,
        cardRepository: leyline.game.data.CardRepository? = null,
    ): HeadlessMatch = MatchFlowHarness.fromSpec(spec, cardRepositoryOverride = cardRepository)
}
