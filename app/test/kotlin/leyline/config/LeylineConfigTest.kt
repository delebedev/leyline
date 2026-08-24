package leyline.config

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import leyline.UnitTag
import leyline.validateWebHead
import java.io.File
import java.nio.file.Path

class LeylineConfigTest :
    FunSpec({

        tags(UnitTag)

        test("checked-in leyline.toml resolves as the normal development baseline") {
            val repoRoot = Path.of(System.getProperty("user.dir")).toFile()
            val file = File(repoRoot, LeylineConfig.FILENAME)
            val resolved = LeylineConfigResolver(baseDir = repoRoot, env = emptyMap()).resolve(file)
            val config = resolved.config

            assertSoftly {
                // Native listeners keep their documented defaults.
                config.native.fdPort shouldBe 30010
                config.native.mdPort shouldBe 30003
                config.native.debugPort shouldBe 8090
                config.native.accountPort shouldBe 9443
                config.native.managementPort shouldBe 8091
                config.native.externalHost shouldBe "localhost"
                config.native.debugBind shouldBe "127.0.0.1"

                // Development posture: mulligan skipped, timer off, default AI speed.
                config.engine.skipMulligan shouldBe true
                config.engine.timer shouldBe false
                config.engine.aiSpeed shouldBe 1.0
                config.engine.draft.picker shouldBe "forge"
                config.engine.dev.strict shouldBe false
                config.engine.dev.strictPass shouldBe false

                // Artifact root resolves against the repository root.
                config.paths.artifacts shouldBe "logs"
                resolved.paths.artifactsRoot.absolutePath shouldBe File(repoRoot, "logs").absolutePath
                resolved.paths.playerDb.absolutePath
                    .shouldEndWith("dev.leyline/player.db")
            }
        }

        test("web head resolves from the checked-in TOML with head-specific validation") {
            val repoRoot = Path.of(System.getProperty("user.dir")).toFile()
            val file = File(repoRoot, LeylineConfig.FILENAME)
            val resolved = LeylineConfigResolver(baseDir = repoRoot, env = emptyMap()).resolve(file)

            assertSoftly {
                // One file supplies both heads.
                resolved.config.native.fdPort shouldBe 30010
                resolved.config.web.port shouldBe 8080
                resolved.config.web.host shouldBe "127.0.0.1"
                resolved.config.web.rateLimitEnabled shouldBe true
                resolved.config.engine.skipMulligan shouldBe true
                // The web head requires an external auth secret; the native head does not.
                shouldThrow<IllegalArgumentException> { validateWebHead(resolved.config.web) }
                resolved.config.native.validate()
            }
        }

        test("listener overrides in the TOML are visible through the endpoints derivation") {
            val repoRoot = Path.of(System.getProperty("user.dir")).toFile()
            val file = File(repoRoot, LeylineConfig.FILENAME)
            val resolved = LeylineConfigResolver(baseDir = repoRoot, env = emptyMap()).resolve(file)
            val endpoints = nativeEndpoints(resolved.config.native)

            assertSoftly {
                endpoints.frontDoorPort shouldBe 30010
                endpoints.matchDoorPort shouldBe 30003
                endpoints.advertisedFdUri shouldBe "localhost:30010"
            }
        }
    })
