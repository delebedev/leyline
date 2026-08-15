package leyline.architecture

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import leyline.UnitTag
import java.nio.file.Path

class PlaybackSafePointBoundaryTest :
    FunSpec({
        tags(UnitTag)

        val cwd = Path.of("").toAbsolutePath()
        val sourceRoot =
            sequenceOf(cwd.resolve("src/main/kotlin"), cwd.resolve("engine/src/main/kotlin"))
                .first { it.resolve("leyline/game/GamePlayback.kt").toFile().isFile }

        test("all playback callbacks request cuts and one safe-point closer owns output") {
            val source = sourceRoot.resolve("leyline/game/GamePlayback.kt").toFile().readText()
            val visitors =
                source
                    .substringAfter("override fun visit(ev: GameEventLandPlayed)")
                    .substringBefore("fun onMainLoopStepCompleted()")
            assertSoftly {
                source shouldContain "bridge.cutCoordinator.flushPlaybackCut("
                visitors.split("override fun visit(").size - 1 shouldBe 10
                listOf(
                    "closeBundleFrame(",
                    "materializePlaybackCut(",
                    "compilePlaybackCut(",
                    "commitProjection(",
                    "queue.add(",
                    "Thread.sleep(",
                ).forEach(visitors::shouldNotContain)
                source shouldNotContain "captureLegacyCombatCheckpoint("
                source shouldNotContain "captureAndPause("
                source shouldNotContain "pendingResolutionFrame"
                source shouldNotContain "shouldAwaitResolutionBoundary"
            }
            val coordinator = sourceRoot.resolve("leyline/bridge/coord/MatchCutCoordinator.kt").toFile().readText()
            assertSoftly {
                coordinator shouldContain "bridge.closeBundleFrame(seatId.value)"
                coordinator shouldContain "PendingCut("
                coordinator shouldContain "feed.builder.materializePlaybackCut("
                coordinator shouldContain "feed.builder.compilePlaybackCut("
            }
        }

        test("playback producers preserve the shared frame lock order") {
            val source = sourceRoot.resolve("leyline/bridge/coord/MatchCutCoordinator.kt").toFile().readText()
            val producer = source.substringAfter("fun flushPlaybackCut(").substringBefore("fun acknowledgeExternalFrame(")
            val counter = producer.indexOf("synchronized(counter)")
            val projection = producer.indexOf("synchronized(bridge.projectionBuildLock)")
            val feed = producer.indexOf("synchronized(feedLock)")
            (counter in 0 until projection && projection in 0 until feed) shouldBe true
        }

        test("migrated session paths do not compile or close state frames") {
            val migrated =
                listOf(
                    "leyline/match/ActionPerformer.kt",
                    "leyline/match/AutoPassEngine.kt",
                    "leyline/match/CombatHandler.kt",
                    "leyline/match/NumericInputHandler.kt",
                    "leyline/match/OptionalActionHandler.kt",
                )
            migrated.size shouldBe 5
            migrated.forEach { relative ->
                val source = sourceRoot.resolve(relative).toFile().readText()
                source shouldNotContain "stateOnlyDiff("
                source shouldNotContain "closeBundleFrame("
            }

            val spectator = sourceRoot.resolve("leyline/match/SpectatorSession.kt").toFile().readText()
            spectator shouldContain "bundleBuilder.stateOnlyDiff("
            spectator shouldNotContain "closeBundleFrame("

            val session = sourceRoot.resolve("leyline/match/MatchSession.kt").toFile().readText()
            session shouldNotContain "sendLegacyPromptState("

            val targetingHandler = sourceRoot.resolve("leyline/match/TargetingHandler.kt").toFile().readText()
            val searchResponse = targetingHandler.substringAfter("fun onSearchResp(").substringBefore("// --- Helpers ---")
            assertSoftly {
                searchResponse shouldContain "cutCoordinator.search.submit("
                searchResponse shouldNotContain "ctx.game"
                searchResponse shouldNotContain "findById("
                searchResponse shouldNotContain "getZone("
                targetingHandler shouldNotContain "SearchPromptInteractionHandler"
            }
        }

        test("every game-loop launch uses the pre-start playback pipeline") {
            val source = sourceRoot.resolve("leyline/game/state/GameBridge.kt").toFile().readText()
            source.split("registerPlaybackPipeline(").size - 1 shouldBe 4
            val registrations = source.indicesOf("registerPlaybackPipeline(g,")
            val launches =
                listOf(
                    source.indexOf("loop.start()"),
                    source.indexOf("loop.start(startGameHook)"),
                    source.indexOf("loop.startFromCurrentState()"),
                )
            assertSoftly {
                registrations.size shouldBe 3
                registrations.zip(launches).all { (registration, launch) -> registration in 0 until launch } shouldBe true
                source shouldContain "it.runtimeBindings = leyline.bridge.handoff.PromptRuntimeBindings()"
            }
        }
    })

private fun String.indicesOf(value: String): List<Int> =
    buildList {
        var start = 0
        while (true) {
            val index = indexOf(value, start)
            if (index < 0) return@buildList
            add(index)
            start = index + value.length
        }
    }
