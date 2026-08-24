package leyline.config

/**
 * Legacy translation from the resolved settings graph to the engine
 * [MatchConfig] surface still consumed by match-session composition.
 *
 * Pure field mapping — no policy. Introduced by the native configuration
 * slice and removed by the compatibility slice that deletes MatchConfig.
 */
object LegacyMatchConfigAdapter {
    fun from(settings: LeylineConfig): MatchConfig =
        MatchConfig(
            server =
                ServerConfig(
                    fdPort = settings.native.fdPort,
                    mdPort = settings.native.mdPort,
                    debugPort = settings.native.debugPort,
                    accountPort = settings.native.accountPort,
                    managementPort = settings.native.managementPort,
                    bridgeTimeoutMs = settings.engine.bridgeTimeoutMs,
                    promptFailsafeMs = settings.engine.promptFailsafeMs,
                    aiTurnWaitMs = settings.engine.aiTurnWaitMs,
                    mulliganWaitMs = settings.engine.mulliganWaitMs,
                    // Player state now resolves from [PathSettings]; native and
                    // web heads use ResolvedPaths.playerDb directly.
                    playerDb = "",
                ),
            game =
                GameConfig(
                    seed = settings.engine.seed,
                    dieRollWinner = settings.engine.dieRollWinner,
                    skipMulligan = settings.engine.skipMulligan,
                    timer = settings.engine.timer,
                    aiDeck = settings.engine.aiDeck,
                    spectatorMode = settings.engine.spectatorMode,
                ),
            ai = AiConfig(speed = settings.engine.aiSpeed),
            draft = DraftConfig(picker = settings.engine.draft.picker, modelDir = settings.engine.draft.modelDir),
            dev =
                DevConfig(
                    strict = settings.engine.dev.strict,
                    strictPass = settings.engine.dev.strictPass,
                    copilotAutopush = settings.engine.dev.copilotAutopush,
                    copilotBridgeUrl = settings.engine.dev.copilotBridgeUrl,
                ),
        )
}
