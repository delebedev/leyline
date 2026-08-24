package leyline.config

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import leyline.UnitTag
import java.io.File
import java.nio.file.Path

class LeylineConfigTest :
    FunSpec({

        tags(UnitTag)

        test("checked-in leyline.toml resolves as the normal development baseline") {
            val repoRoot = Path.of(System.getProperty("user.dir")).toFile()
            val file = File(repoRoot, LeylineConfig.FILENAME)
            val resolved = LeylineConfigResolver(baseDir = repoRoot, env = emptyMap()).resolve(file)

            assertSoftly {
                resolved.config shouldBe LeylineConfig()
                resolved.paths.artifactsRoot.absolutePath shouldBe File(repoRoot, "logs").absolutePath
                resolved.paths.playerDb.absolutePath
                    .shouldEndWith("dev.leyline/player.db")
            }
        }
    })
