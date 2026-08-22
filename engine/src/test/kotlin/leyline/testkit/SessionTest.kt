package leyline.testkit

import io.kotest.core.spec.style.FunSpec
import io.kotest.core.test.TestScope
import leyline.IntegrationTag
import leyline.game.bundle.InvariantSelection
import leyline.testkit.*
import leyline.tooling.headless.HeadlessMatch
import leyline.tooling.headless.HeadlessMatchFactory
import leyline.tooling.headless.MatchSpec
import leyline.tooling.headless.ScriptedAction
import leyline.tooling.headless.diagnostics
import kotlin.time.Duration

/**
 * Base class for session-tier interaction tests.
 *
 * Parallel to [BoardTest] (board/bridge tier). Never mix in one file.
 * Auto-wires IntegrationTag; each [session] owns one headless session for
 * the length of one test and dumps diagnostics if that test fails.
 *
 * ```
 * class FooSessionTest : SessionTest({
 *     session(
 *         "ability resolves and deals damage",
 *         puzzle = """
 *             humanbattlefield=Card Name
 *             ...
 *         """,
 *     ) {
 *         activateAbility("Card Name")
 *         selectTargets(listOf(OPPONENT_SEAT))
 *         passUntilResolved()
 *         ai.life shouldBe 4
 *     }
 * })
 * ```
 *
 * The block's receiver is the headless session, so every game action, prompt response
 * and state query resolves directly against it — this base owns lifecycle and
 * naming, not behavior. A helper shared by several tests in one spec takes the
 * match as its receiver too (`fun HeadlessMatch.castAndResolve()`); a
 * helper declared without that receiver has no game to act on.
 */
// `abstract` keeps Kotest's auto-discovery from trying to instantiate the base
// class directly (no zero-arg constructor — only the `body` lambda variant).
@Suppress("UnnecessaryAbstractClass")
abstract class SessionTest(
    body: SessionTest.() -> Unit,
) : FunSpec() {
    companion object {
        /** Seat ID for the human player (tests always use seat 1). */
        const val HUMAN_SEAT = 1

        /** Seat ID for the AI / opponent. */
        const val OPPONENT_SEAT = 2

        /** A full `.pzl` text carries its own metadata section; a `[state]` body does not. */
        private val METADATA_SECTION = Regex("""(?m)^\s*\[metadata]""")
    }

    init {
        tags(IntegrationTag)
        body()
    }

    /**
     * Declare one session-tier test backed by a fresh headless session.
     *
     * Give at most one game source:
     * - [puzzle] — puzzle text. A `[state]` body gets `[metadata]` synthesized
     *   from [name] and [turns]; text that already has a `[metadata]` section
     *   is used verbatim (and then [turns] does not apply).
     * - [puzzleFile] — classpath resource, e.g. `puzzles/bolt-face.pzl`.
     * - neither — a normal game (mulligan + keep) using [deckList].
     *
     * [timeout] bounds a test that can hang the engine loop rather than fail.
     *
     * When the block throws, harness diagnostics are printed before the
     * failure propagates; the harness is shut down either way.
     */
    // EmptyAssertion/WeakAssertionOnly inspect `session(...)` at spec call
    // sites; this is the wrapper that produces those blocks, so its own
    // `test(name)` carries no assertion by construction.
    @Suppress("LongParameterList", "EmptyAssertion")
    internal fun session(
        name: String,
        puzzle: String? = null,
        puzzleFile: String? = null,
        deckList: String? = null,
        turns: Int = 1,
        seed: Long = 42L,
        promptTimeoutMs: Long? = null,
        validating: Boolean = true,
        validation: InvariantSelection = leyline.tooling.headless.defaultHeadlessValidation(validating),
        aiScript: List<ScriptedAction>? = null,
        timeout: Duration? = null,
        block: suspend HeadlessMatch.() -> Unit,
    ) {
        require(puzzle == null || puzzleFile == null) {
            "session('$name'): give at most one of puzzle or puzzleFile"
        }
        require(deckList == null || (puzzle == null && puzzleFile == null)) {
            "session('$name'): deckList applies to a normal game, not a puzzle"
        }
        val puzzleText = puzzle?.let { puzzleTextFor(it, name, turns) }
        val run: suspend TestScope.() -> Unit = {
            val match =
                HeadlessMatchFactory.create(
                    MatchSpec(
                        seed = seed,
                        promptTimeoutMs = promptTimeoutMs,
                        deckList = deckList,
                        puzzleText = puzzleText,
                        puzzleResource = puzzleFile,
                        aiScript = aiScript,
                        validating = validating,
                        validation = validation,
                    ),
                )
            val harness = match
            try {
                match.start()
                harness.block()
            } catch (failure: Throwable) {
                match.diagnostics(name)
                throw failure
            } finally {
                match.close()
            }
        }
        if (timeout == null) test(name, run) else test(name).config(timeout = timeout, test = run)
    }

    private fun puzzleTextFor(
        puzzle: String,
        name: String,
        turns: Int,
    ): String =
        if (METADATA_SECTION.containsMatchIn(puzzle)) {
            puzzle
        } else {
            buildString {
                appendLine("[metadata]")
                appendLine("Name:${name.substringBefore('\n').replace(':', ' ')}")
                appendLine("Goal:Win")
                appendLine("Turns:$turns")
                appendLine()
                appendLine("[state]")
                append(puzzle.trimIndent())
            }
        }
}
