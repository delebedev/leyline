package leyline.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaMethodCall
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.architecture.EngineArchitecture.kotlinName
import leyline.architecture.EngineArchitecture.named

/**
 * Pins the playback safe point: playback callbacks mark where a cut may happen,
 * and one coordinator turns that mark into an output frame.
 *
 * Splitting the mark from the work is what keeps a cut atomic. A callback that
 * closed its own frame, compiled its own batch, or slept would produce output
 * from a state nobody promised was quiescent, so the rules here are about who
 * may call the safe-point primitives, not about what the callbacks compute.
 */
class PlaybackSafePointBoundaryTest :
    FunSpec({
        tags(UnitTag)

        val classes = EngineArchitecture.mainClasses
        val sourceRoot = EngineArchitecture.sourceRoot

        test("playback callbacks only request cuts") {
            classes()
                .that()
                .haveFullyQualifiedName(GAME_PLAYBACK)
                .should()
                .callMethodWhere(flushPlaybackCut)
                .because("a callback marks the safe point and hands it to the cut coordinator")
                .check(classes)

            noClasses()
                .that()
                .haveNameMatching(named(GAME_PLAYBACK))
                .should()
                .callMethodWhere(safePointWork)
                .because("closing, materializing, compiling and waiting all belong to the cut coordinator")
                .check(classes)
        }

        test("the cut coordinator is the single safe-point closer") {
            listOf(closeBundleFrame, materializePlaybackCut, compilePlaybackCut).forEach { primitive ->
                classes()
                    .that()
                    .haveFullyQualifiedName(CUT_COORDINATOR)
                    .should()
                    .callMethodWhere(primitive)
                    .because("the coordinator owns the whole safe point, not a part of it")
                    .check(classes)
            }

            classes()
                .that()
                .haveFullyQualifiedName(CUT_COORDINATOR)
                .should()
                .dependOnClassesThat()
                .haveFullyQualifiedName("leyline.game.PendingCut")
                .because("a materialized cut is retained as a value between materialize and compile")
                .check(classes)
        }

        test("playback producers preserve the shared frame lock order") {
            // Source-level, deliberately: `synchronized` is inlined to bare monitor
            // instructions, so the nesting order the deadlock argument rests on is
            // not visible in the imported class model.
            val producer =
                sourceRoot
                    .resolve("leyline/bridge/coord/MatchCutCoordinator.kt")
                    .toFile()
                    .readText()
                    .substringAfter("fun flushPlaybackCut(")
                    .substringBefore("fun acknowledgeExternalFrame(")
            val order =
                listOf("counter", "bridge.projectionBuildLock", "feedLock")
                    .map { it to producer.indexOf("synchronized($it)") }
            val outOfOrder =
                order
                    .zipWithNext()
                    .filterNot { (outer, inner) -> outer.second in 0 until inner.second }
                    .map { (outer, inner) -> "${outer.first}@${outer.second} must precede ${inner.first}@${inner.second}" }

            withClue("flushPlaybackCut lock nesting (offset -1 means the lock is gone): $order") {
                outOfOrder.shouldBeEmpty()
            }
        }

        test("migrated session paths neither build state-only diffs nor close frames") {
            noClasses()
                .that()
                .haveNameMatching(
                    named(
                        "leyline.match.ActionPerformer",
                        "leyline.match.AutoPassEngine",
                        "leyline.match.CombatHandler",
                        "leyline.match.NumericInputHandler",
                        "leyline.match.OptionalActionHandler",
                    ),
                ).should()
                .callMethodWhere(stateFrameOwnership)
                .because("these paths hand their frame to the cut coordinator")
                .check(classes)
        }

        test("the spectator path builds its own state-only diff but never closes the frame") {
            classes()
                .that()
                .haveFullyQualifiedName("leyline.match.SpectatorSession")
                .should()
                .callMethodWhere(stateOnlyDiff)
                .because("a spectator frame is projected outside the cut coordinator")
                .check(classes)

            noClasses()
                .that()
                .haveNameMatching(named("leyline.match.SpectatorSession"))
                .should()
                .callMethodWhere(closeBundleFrame)
                .because("the cut coordinator still owns frame closure")
                .check(classes)
        }

        test("every game-loop launch registers its playback pipeline first") {
            val bridge = classes.get(GAME_BRIDGE)
            val registrations = bridge.callsFromSelfNamed("registerPlaybackPipeline")
            val launches =
                bridge.methodCallsFromSelf.filter {
                    it.targetOwner.name == "leyline.bridge.coord.GameLoopController" &&
                        kotlinName(it.target.name) in setOf("start", "startFromCurrentState")
                }

            val unregistered =
                launches
                    .filter { launch ->
                        registrations.none { it.origin == launch.origin && it.lineNumber < launch.lineNumber }
                    }.map { "${it.origin.fullName}:${it.lineNumber}" }
                    .sorted()

            assertSoftly {
                launches.shouldNotBeEmpty()
                withClue("game-loop launches with no earlier registerPlaybackPipeline in the same method") {
                    unregistered.shouldBeEmpty()
                }
            }
        }

        test("tearing down a match replaces the prompt runtime bindings") {
            // A torn-down bridge that kept its bindings would let a stale
            // coordinator answer the next match's prompts.
            val constructed =
                classes
                    .get(GAME_BRIDGE)
                    .constructorCallsFromSelf
                    .filter { kotlinName(it.origin.name) == "teardownResources" }
                    .map { it.targetOwner.name }

            val bindings = "leyline.bridge.handoff.PromptRuntimeBindings"
            withClue("types constructed by GameBridge.teardownResources: $constructed") {
                constructed.filter { it == bindings } shouldBe listOf(bindings)
            }
        }
    })

private const val GAME_PLAYBACK = "leyline.game.GamePlayback"
private const val CUT_COORDINATOR = "leyline.bridge.coord.MatchCutCoordinator"
private const val GAME_BRIDGE = "leyline.game.state.GameBridge"

/**
 * Calls out of this class to [name], matched on the Kotlin spelling of the
 * target so `internal` members and their generated companions resolve back to
 * one name.
 */
private fun JavaClass.callsFromSelfNamed(name: String) = methodCallsFromSelf.filter { kotlinName(it.target.name) == name }

/** A call to [name] on [owner], tolerant of the `$module` suffix on `internal` members. */
private fun methodCall(
    owner: String,
    name: String,
    description: String,
) = object : DescribedPredicate<JavaMethodCall>(description) {
    override fun test(call: JavaMethodCall): Boolean = call.targetOwner.name == owner && kotlinName(call.target.name) == name
}

private const val BUNDLE_BUILDER = "leyline.game.bundle.BundleBuilder"

private val stateOnlyDiff = methodCall(BUNDLE_BUILDER, "stateOnlyDiff", "build a state-only diff")

private val materializePlaybackCut =
    methodCall(BUNDLE_BUILDER, "materializePlaybackCut", "materialize a playback cut")

private val compilePlaybackCut =
    methodCall(BUNDLE_BUILDER, "compilePlaybackCut", "compile a playback cut")

private val closeBundleFrame = methodCall(GAME_BRIDGE, "closeBundleFrame", "close a bundle frame")

private val commitProjection = methodCall(GAME_BRIDGE, "commitProjection", "commit a projection")

private val flushPlaybackCut =
    methodCall(CUT_COORDINATOR, "flushPlaybackCut", "flush a requested playback cut")

private val sleep = methodCall("java.lang.Thread", "sleep", "sleep on the playback thread")

private val stateFrameOwnership = stateOnlyDiff.or(closeBundleFrame)

/** Everything the safe point does once a callback has marked it. */
private val safePointWork =
    closeBundleFrame
        .or(materializePlaybackCut)
        .or(compilePlaybackCut)
        .or(commitProjection)
        .or(sleep)
