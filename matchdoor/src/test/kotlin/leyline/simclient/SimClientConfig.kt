package leyline.simclient

import java.io.File

private const val DEFAULT_DECKS = "forest-only,bears,mono-g-curve,mono-r-burn"
private const val DEFAULT_SEEDS = "7,13,42,99,314"

data class SimClientConfig(
    val deckSpec: String = DEFAULT_DECKS,
    val opponentDeck: String? = null,
    val seedSpec: String = DEFAULT_SEEDS,
    val puzzleSpec: String? = null,
    val policy: SimClientPolicyMode = SimClientPolicyMode.Greedy,
    val maxTurns: Int = 25,
    val gameTimeoutSeconds: Long = 120,
    val outDir: File = File("matchdoor/build/simclient"),
    val cardDbPath: String? = null,
    val continueOnException: Boolean = true,
    val strict: Boolean = false,
    val resume: Boolean = false,
    val shardIndex: Int? = null,
    val shardCount: Int? = null,
    val ingestScry: Boolean = false,
    val summaryJson: File? = null,
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
                    cardDbPath = envOrDefault("LEYLINE_CARD_DB"),
                    continueOnException = envOrDefault("SIMCLIENT_CONTINUE_ON_EXCEPTION")?.equals("true", ignoreCase = true) ?: true,
                )
            var i = 0
            while (i < args.size) {
                val arg = args[i]

                fun value(): String {
                    require(i + 1 < args.size) { "$arg requires a value" }
                    i += 1
                    return args[i]
                }

                config =
                    when (arg) {
                        "--decks" -> config.copy(deckSpec = value(), puzzleSpec = null)
                        "--opponent-deck" -> config.copy(opponentDeck = value())
                        "--seeds" -> config.copy(seedSpec = value())
                        "--puzzles" -> config.copy(puzzleSpec = value())
                        "--policy" -> config.copy(policy = SimClientPolicyMode.parse(value()))
                        "--max-turns" -> config.copy(maxTurns = value().toInt())
                        "--game-timeout-seconds" -> config.copy(gameTimeoutSeconds = value().toLong())
                        "--out-dir" -> config.copy(outDir = File(value()))
                        "--card-db" -> config.copy(cardDbPath = value())
                        "--continue-on-exception" -> config.copy(continueOnException = true)
                        "--fail-on-exception" -> config.copy(continueOnException = false)
                        "--strict" -> config.copy(strict = true)
                        "--resume" -> config.copy(resume = true)
                        "--shard-index" -> config.copy(shardIndex = value().toInt())
                        "--shard-count" -> config.copy(shardCount = value().toInt())
                        "--ingest-scry" -> config.copy(ingestScry = true)
                        "--summary-json" -> config.copy(summaryJson = File(value()))
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

                  --decks <a,b>                 Deck matrix, built-in names or data/decks basenames.
                  --opponent-deck <name>        Fixed seat-2 deck; omitted means mirror.
                  --puzzles <a.pzl,b.pzl>       Puzzle matrix instead of decks.
                  --seeds <1..20|1,2,3>         Seed matrix.
                  --policy <greedy|forge-ai>    Prompt policy.
                  --max-turns <n>               Turn cap.
                  --game-timeout-seconds <n>    Per-game wall-clock watchdog.
                  --out-dir <path>              Artifact directory.
                  --card-db <path>              Card database path.
                  --resume                      Skip rows with existing stats.
                  --shard-index <n>             Zero-based shard index.
                  --shard-count <n>             Number of shards.
                  --strict                      Nonzero exit on row failures/validation.
                  --ingest-scry                 Copy log/meta artifacts into ~/.scry/games.
                  --summary-json <path>         Run summary JSON path.
                """.trimIndent(),
            )
        }
    }
}

enum class SimClientPolicyMode {
    Greedy,
    ForgeAi,
    ;

    companion object {
        fun parse(value: String): SimClientPolicyMode =
            when (value.trim().lowercase()) {
                "greedy" -> Greedy
                "forge-ai" -> ForgeAi
                else -> error("unknown SIMCLIENT_POLICY: $value")
            }
    }
}

data class SimClientRunResult(
    val rows: List<SimClientRowResult>,
    val skipped: Int,
) {
    val hasStrictFailures: Boolean =
        rows.any { row ->
            row.stats.completionReason != "natural" &&
                row.stats.completionReason != "terminal-intermission" ||
                row.stats.validationViolationsByCheck.isNotEmpty()
        }
}

data class SimClientRowResult(
    val row: SimClientRow,
    val stats: GameStats,
)

sealed interface SimClientRow {
    val name: String
    val seed: Long
    val runKind: String
    val runLabel: String
    val opponentRunLabel: String?

    val tag: String
        get() {
            val base = opponentRunLabel?.let { "$runLabel-vs-$it" } ?: runLabel
            return "${fileSafeName(base)}-s$seed"
        }

    val identity: String
        get() = listOf(runKind, runLabel, opponentRunLabel.orEmpty(), seed.toString()).joinToString("|")
}

data class DeckSimClientRow(
    override val name: String,
    val deckList: String,
    val opponentName: String?,
    val opponentDeckList: String?,
    override val seed: Long,
) : SimClientRow {
    override val runKind: String = "deck"
    override val runLabel: String = name
    override val opponentRunLabel: String? = opponentName
}

data class PuzzleSimClientRow(
    override val name: String,
    val puzzleText: String,
    override val seed: Long,
) : SimClientRow {
    override val runKind: String = "puzzle"
    override val runLabel: String = name
    override val opponentRunLabel: String? = null
}
