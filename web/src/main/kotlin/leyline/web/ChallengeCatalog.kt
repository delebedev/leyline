package leyline.web

import leyline.config.PuzzleDefinition

data class ChallengeDefinition(
    val summary: ChallengeSummary,
    val puzzle: PuzzleDefinition,
)

/** Static Web product challenges. Keep this catalog independent of the puzzle library. */
class ChallengeCatalog(
    val challenges: List<ChallengeDefinition>,
) {
    init {
        require(challenges.map { it.summary.challengeId }.distinct().size == challenges.size) {
            "Challenge ids must be unique"
        }
    }

    fun summaries(): List<ChallengeSummary> = challenges.map { it.summary }

    fun find(challengeId: String): ChallengeDefinition? = challenges.find { it.summary.challengeId == challengeId }

    companion object {
        fun default(): ChallengeCatalog = ChallengeCatalog(listOf(BOLT))

        private val BOLT =
            ChallengeDefinition(
                summary = ChallengeSummary(challengeId = "bolt-face", name = "Bolt Face"),
                puzzle =
                    PuzzleDefinition(
                        identity = "bolt-face",
                        content =
                            """
                            [metadata]
                            Name:Bolt Face
                            Goal:Cast Lightning Bolt at opponent. The spell resolves and the game continues.
                            Turns:2
                            Difficulty:Easy
                            Description:Mountain on the battlefield, Lightning Bolt in hand.

                            [state]
                            ActivePlayer=Human
                            ActivePhase=Main1
                            HumanLife=20
                            AILife=20

                            humanhand=Lightning Bolt
                            humanbattlefield=Mountain
                            humanlibrary=Mountain
                            ailibrary=Mountain
                            """.trimIndent(),
                    ),
            )
    }
}
