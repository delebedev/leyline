package leyline.tooling.simclient

import java.io.File

private const val DEFAULT_DECKS = "forest-only,bears,mono-g-curve,mono-r-burn"
private const val DEFAULT_SEEDS = "7,13,42,99,314"
private const val DEFAULT_QUARANTINE_FILE = "data/simclient/quarantine.txt"

data class SimClientConfig(
    val deckSpec: String = DEFAULT_DECKS,
    val opponentDeck: String? = null,
    val seedSpec: String = DEFAULT_SEEDS,
    val puzzleSpec: String? = null,
    val policy: SimClientPolicyMode = SimClientPolicyMode.Greedy,
    val maxTurns: Int = 25,
    val gameTimeoutSeconds: Long = 120,
    val outDir: File = File("engine/build/simclient"),
    val continueOnException: Boolean = true,
    val strict: Boolean = false,
    val resume: Boolean = false,
    val shardIndex: Int? = null,
    val shardCount: Int? = null,
    val ingestScry: Boolean = false,
    val summaryJson: File? = null,
    val excludeCards: String = "",
    val excludeCardsFile: File? = defaultQuarantineFile(),
    val excludePolicy: SimClientExcludePolicy = SimClientExcludePolicy.ReplaceBasic,
    val verbose: Boolean = false,
) {
    init {
        require(maxTurns > 0) { "--max-turns must be > 0" }
        require(gameTimeoutSeconds > 0) { "--game-timeout-seconds must be > 0" }
    }

    fun validate() {
        if (shardIndex != null || shardCount != null) {
            require(shardIndex != null && shardCount != null) { "--shard-index and --shard-count must be set together" }
            require(shardCount > 0) { "--shard-count must be > 0" }
            require(shardIndex in 0 until shardCount) { "--shard-index must be in [0, shard-count)" }
        }
    }

    companion object {
        @Suppress("CyclomaticComplexMethod")
        fun parse(
            args: List<String>,
            env: Map<String, String>,
        ): SimClientConfig? {
            fun envOrDefault(name: String): String? = env[name]?.takeIf { it.isNotBlank() }
            var config =
                SimClientConfig(
                    deckSpec = envOrDefault("SIMCLIENT_DECKS") ?: DEFAULT_DECKS,
                    opponentDeck = envOrDefault("SIMCLIENT_OPPONENT_DECK"),
                    seedSpec = envOrDefault("SIMCLIENT_SEEDS") ?: DEFAULT_SEEDS,
                    puzzleSpec = envOrDefault("SIMCLIENT_PUZZLE"),
                    policy = SimClientPolicyMode.parse(envOrDefault("SIMCLIENT_POLICY") ?: "greedy"),
                    maxTurns = (envOrDefault("SIMCLIENT_MAX_TURNS") ?: "25").toInt().coerceAtLeast(1),
                    gameTimeoutSeconds = (envOrDefault("SIMCLIENT_GAME_TIMEOUT_SECONDS") ?: "120").toLong().coerceAtLeast(1),
                    continueOnException = envOrDefault("SIMCLIENT_CONTINUE_ON_EXCEPTION")?.equals("true", ignoreCase = true) ?: true,
                    excludeCards = envOrDefault("SIMCLIENT_EXCLUDE_CARDS").orEmpty(),
                    excludeCardsFile = envOrDefault("SIMCLIENT_EXCLUDE_CARDS_FILE")?.let(::File) ?: defaultQuarantineFile(),
                    excludePolicy = SimClientExcludePolicy.parse(envOrDefault("SIMCLIENT_EXCLUDE_POLICY") ?: "replace-basic"),
                    verbose = envOrDefault("SIMCLIENT_VERBOSE")?.equals("true", ignoreCase = true) ?: false,
                )
            var i = 0
            while (i < args.size) {
                val arg = args[i]

                fun value(allowSpaces: Boolean = false): String {
                    require(i + 1 < args.size) { "$arg requires a value" }
                    i += 1
                    if (!allowSpaces) return args[i]
                    val values = mutableListOf(args[i])
                    while (i + 1 < args.size && !args[i + 1].startsWith("--")) {
                        i += 1
                        values += args[i]
                    }
                    return values.joinToString(" ")
                }

                config =
                    when (arg) {
                        "--decks" -> config.copy(deckSpec = value(allowSpaces = true), puzzleSpec = null)
                        "--opponent-deck" -> config.copy(opponentDeck = value(allowSpaces = true))
                        "--seeds" -> config.copy(seedSpec = value())
                        "--puzzles" -> config.copy(puzzleSpec = value(allowSpaces = true))
                        "--policy" -> config.copy(policy = SimClientPolicyMode.parse(value()))
                        "--max-turns" -> config.copy(maxTurns = value().toInt())
                        "--game-timeout-seconds" -> config.copy(gameTimeoutSeconds = value().toLong())
                        "--out-dir" -> config.copy(outDir = File(value()))
                        "--continue-on-exception" -> config.copy(continueOnException = true)
                        "--fail-on-exception" -> config.copy(continueOnException = false)
                        "--strict" -> config.copy(strict = true)
                        "--resume" -> config.copy(resume = true)
                        "--shard-index" -> config.copy(shardIndex = value().toInt())
                        "--shard-count" -> config.copy(shardCount = value().toInt())
                        "--ingest-scry" -> config.copy(ingestScry = true)
                        "--summary-json" -> config.copy(summaryJson = File(value()))
                        "--exclude-cards" -> config.copy(excludeCards = value(allowSpaces = true))
                        "--exclude-cards-file" -> config.copy(excludeCardsFile = File(value()))
                        "--no-exclude-cards-file" -> config.copy(excludeCardsFile = null)
                        "--exclude-policy" -> config.copy(excludePolicy = SimClientExcludePolicy.parse(value()))
                        "--verbose" -> config.copy(verbose = true)
                        "--help", "-h" -> {
                            printUsage()
                            return null
                        }
                        else -> error("unknown simclient arg: $arg")
                    }
                i += 1
            }
            config.validate()
            return config
        }

        private fun printUsage() {
            println(
                """
                Usage: simclient [options]

                Card data comes from the client database — LEYLINE_CARD_DB
                override or standard-location autodiscovery; every row
                requires it.

                  --decks <a,b>                 Deck matrix, data/decks basenames.
                  --opponent-deck <name>        Fixed seat-2 deck; omitted means mirror.
                  --puzzles <a.pzl,b.pzl>       Puzzle matrix instead of decks.
                  --seeds <1..20|1,2,3>         Seed matrix.
                  --policy <greedy|forge-ai|shadow-ai|snapshot-shadow|snapshot>
                                                  snapshot drives reconstructed-state proposals; snapshot-shadow compares.
                  --max-turns <n>               Turn cap.
                  --game-timeout-seconds <n>    Per-game wall-clock watchdog.
                  --out-dir <path>              Artifact directory.
                  --continue-on-exception       Keep running and write exception rows.
                  --fail-on-exception           Abort on exception rows.
                  --resume                      Skip rows with existing stats.
                  --shard-index <n>             Zero-based shard index.
                  --shard-count <n>             Number of shards.
                  --strict                      Nonzero exit on row failures/validation.
                  --ingest-scry                 Copy log/meta artifacts into ~/.scry/games.
                  --summary-json <path>         Run summary JSON path.
                  --exclude-cards <a,b>         Quarantine card names or grpIds for deck rows.
                  --exclude-cards-file <path>   Quarantine file; defaults to data/simclient/quarantine.txt when present.
                  --no-exclude-cards-file       Ignore the default quarantine file.
                  --exclude-policy <policy>     replace-basic|skip-deck.
                  --verbose                     Keep detailed console logging; default stdout is compact.
                """.trimIndent(),
            )
        }
    }
}

private fun defaultQuarantineFile(): File? =
    listOf(
        File(DEFAULT_QUARANTINE_FILE),
        File("../$DEFAULT_QUARANTINE_FILE"),
        File("../../$DEFAULT_QUARANTINE_FILE"),
    ).firstOrNull { it.exists() }

enum class SimClientPolicyMode {
    Greedy,
    ForgeAi,
    ShadowAi,

    /**
     * Greedy driver plus the [SnapshotShadowProbe]: each prompt the seat answers
     * is proposed twice — on the live game and on a game hydrated from its wire
     * state — to measure snapshot hydration fidelity as desired-decision agreement.
     */
    SnapshotShadow,

    /** Rebuild each prompted position and submit the response produced by [leyline.copilot.SnapshotConsult]. */
    Snapshot,
    ;

    companion object {
        fun parse(value: String): SimClientPolicyMode =
            when (value.trim().lowercase()) {
                "greedy" -> Greedy
                "forge-ai" -> ForgeAi
                "shadow-ai" -> ShadowAi
                "snapshot-shadow" -> SnapshotShadow
                "snapshot" -> Snapshot
                else -> error("unknown SIMCLIENT_POLICY: $value")
            }
    }
}

enum class SimClientExcludePolicy {
    ReplaceBasic,
    SkipDeck,
    ;

    companion object {
        fun parse(value: String): SimClientExcludePolicy =
            when (value.trim().lowercase()) {
                "replace-basic" -> ReplaceBasic
                "skip-deck" -> SkipDeck
                else -> error("unknown --exclude-policy: $value")
            }
    }
}

data class SimClientRunResult(
    val rows: List<SimClientRowResult>,
    val skipped: Int,
) {
    val hasStrictFailures: Boolean =
        rows.any { row ->
            isStrictFailure(row.stats)
        }
}

fun isStrictFailure(stats: GameStats): Boolean = failureClass(stats) in strictFailureClasses

private val strictFailureClasses = setOf("exception", "wall-timeout", "validation", "log-error")

data class SimClientRowResult(
    val row: SimClientRow,
    val stats: GameStats,
)
