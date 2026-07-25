package leyline.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import dev.detekt.test.utils.compileContentForTest
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Path

class NoGameInMappersTest :
    FunSpec({

        fun lintAt(code: String, path: String): Int {
            val file = compileContentForTest(code, Path.of(path))
            return NoGameInMappers(Config.empty).lint(file).size
        }

        test("forge.game.Game import inside a mapper is flagged") {
            val code = """
                package leyline.game.mapping
                import forge.game.Game
                class X
            """.trimIndent()
            lintAt(code, "/tmp/leyline/game/mapping/X.kt") shouldBe 1
        }

        test("forge.game.Game import inside StateMapper is flagged") {
            val code = """
                package leyline.game.mapping
                import forge.game.Game
                class StateMapper
            """.trimIndent()
            lintAt(code, "/tmp/leyline/game/mapping/StateMapper.kt") shouldBe 1
        }

        test("forge.game.Game import inside GsmBuilder is flagged") {
            val code = """
                package leyline.game.bundle
                import forge.game.Game
                class GsmBuilder
            """.trimIndent()
            lintAt(code, "/tmp/leyline/game/bundle/GsmBuilder.kt") shouldBe 1
        }

        test("forge.game.Game import inside BundleBuilder is allowed") {
            val code = """
                package leyline.game
                import forge.game.Game
                class BundleBuilder
            """.trimIndent()
            lintAt(code, "/tmp/leyline/game/BundleBuilder.kt") shouldBe 0
        }

        test("forge.game.Game import inside snapshot package is allowed") {
            val code = """
                package leyline.game.snapshot
                import forge.game.Game
                class SnapshotCapture
            """.trimIndent()
            lintAt(code, "/tmp/leyline/game/snapshot/SnapshotCapture.kt") shouldBe 0
        }

        test("forge.game.Game import inside GameEventCollector is allowed (EventBus subscriber)") {
            val code = """
                package leyline.game
                import forge.game.Game
                class GameEventCollector
            """.trimIndent()
            lintAt(code, "/tmp/leyline/game/GameEventCollector.kt") shouldBe 0
        }

        test("unrelated import inside a mapper is not flagged") {
            val code = """
                package leyline.game.mapping
                import some.other.Class
                class Y
            """.trimIndent()
            lintAt(code, "/tmp/leyline/game/mapping/Y.kt") shouldBe 0
        }
    })
