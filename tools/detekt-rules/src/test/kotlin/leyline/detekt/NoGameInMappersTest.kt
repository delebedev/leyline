package leyline.detekt

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.test.lint
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

class NoGameInMappersTest :
    FunSpec({

        fun lintAt(code: String, path: String): Int {
            val file = File(path).also { it.parentFile?.mkdirs(); it.writeText(code) }
            return try {
                NoGameInMappers(Config.empty).lint(file.toPath()).size
            } finally {
                file.delete()
            }
        }

        test("forge.game.Game import inside a mapper is flagged") {
            val code = """
                package leyline.game.mapper
                import forge.game.Game
                class X
            """.trimIndent()
            lintAt(code, "/tmp/leyline/game/mapper/X.kt") shouldBe 1
        }

        test("forge.game.Game import inside StateMapper is flagged") {
            val code = """
                package leyline.game
                import forge.game.Game
                class StateMapper
            """.trimIndent()
            lintAt(code, "/tmp/leyline/game/StateMapper.kt") shouldBe 1
        }

        test("forge.game.Game import inside GsmBuilder is flagged") {
            val code = """
                package leyline.game
                import forge.game.Game
                class GsmBuilder
            """.trimIndent()
            lintAt(code, "/tmp/leyline/game/GsmBuilder.kt") shouldBe 1
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
                package leyline.game.mapper
                import some.other.Class
                class Y
            """.trimIndent()
            lintAt(code, "/tmp/leyline/game/mapper/Y.kt") shouldBe 0
        }
    })
