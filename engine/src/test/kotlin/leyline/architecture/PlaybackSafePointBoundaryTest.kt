package leyline.architecture

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
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

        test("ordinary playback has one safe-point capture path and named combat exceptions") {
            val source = sourceRoot.resolve("leyline/game/GamePlayback.kt").toFile().readText()
            assertSoftly {
                source
                    .substringBefore("override fun visit(ev: GameEventAttackersDeclared)")
                    .substringAfter("override fun visit(ev: GameEventLandPlayed)") shouldNotContain "captureAndPause("
                source.split("captureLegacyCombatCheckpoint(").size - 1 shouldBe 5
                source shouldNotContain "pendingResolutionFrame"
                source shouldNotContain "shouldAwaitResolutionBoundary"
            }
        }

        test("playback producers preserve the shared frame lock order") {
            val source = sourceRoot.resolve("leyline/game/GamePlayback.kt").toFile().readText()
            val ordinary = source.substringAfter("private fun flushOrdinaryCut()").substringBefore("private fun terminate(")
            val combat = source.substringAfter("private fun captureAndPause(").substringBefore("private fun requestCut(")

            listOf(ordinary, combat).forEach { producer ->
                val counter = producer.indexOf("synchronized(counter)")
                val projection = producer.indexOf("synchronized(bridge.projectionBuildLock)")
                val queue = producer.indexOf("synchronized(queueLock)")
                (counter in 0 until projection && projection in 0 until queue) shouldBe true
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
            registrations.size shouldBe 3
            registrations.zip(launches).all { (registration, launch) -> registration in 0 until launch } shouldBe true
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
