package leyline.config

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.UnitTag
import java.io.File
import java.nio.file.Files

/**
 * Native-head assembly proofs: the resolved settings produce consistent
 * listener ports, advertised endpoints, player state, and artifact roots —
 * including two simultaneous instances from one base TOML.
 */
class NativeAssemblyTest :
    FunSpec({

        tags(UnitTag)

        fun tmpDir(): File = Files.createTempDirectory("leyline-native-assembly-test").toFile()

        fun writeToml(dir: File): File =
            File(dir, LeylineConfig.FILENAME).apply {
                writeText(
                    """
                    [native]
                    fd_port = 30010
                    md_port = 30003
                    debug_port = 8090
                    account_port = 9443
                    management_port = 8091
                    """.trimIndent(),
                )
            }

        test("environment overrides reach every native listener and the advertised endpoint") {
            val dir = tmpDir()
            val file = writeToml(dir)
            val env =
                mapOf(
                    "LEYLINE_NATIVE_FD_PORT" to "31010",
                    "LEYLINE_NATIVE_MD_PORT" to "31003",
                    "LEYLINE_NATIVE_DEBUG_PORT" to "18100",
                    "LEYLINE_NATIVE_ACCOUNT_PORT" to "19443",
                    "LEYLINE_NATIVE_MANAGEMENT_PORT" to "18091",
                    "LEYLINE_NATIVE_EXTERNAL_HOST" to "192.168.1.5",
                )
            val resolved = LeylineConfigResolver(baseDir = dir, env = env).resolve(file)
            val endpoints = nativeEndpoints(resolved.config.native)

            assertSoftly {
                endpoints.frontDoorPort shouldBe 31010
                endpoints.matchDoorPort shouldBe 31003
                endpoints.debugPort shouldBe 18100
                endpoints.accountPort shouldBe 19443
                endpoints.managementPort shouldBe 18091
                endpoints.externalHost shouldBe "192.168.1.5"
                endpoints.advertisedFdUri shouldBe "192.168.1.5:31010"
            }
        }

        test("advertised endpoint follows the fd port unless explicitly overridden") {
            val dir = tmpDir()
            val file = writeToml(dir)
            val defaultEndpoints = nativeEndpoints(LeylineConfigResolver(baseDir = dir, env = emptyMap()).resolve(file).config.native)
            defaultEndpoints.advertisedFdUri shouldBe "localhost:30010"

            val rehosted = LeylineConfigResolver(baseDir = dir, env = mapOf("LEYLINE_NATIVE_FD_PORT" to "31010")).resolve(file)
            nativeEndpoints(rehosted.config.native).advertisedFdUri shouldBe "localhost:31010"

            val explicitAuthority =
                LeylineConfigResolver(
                    baseDir = dir,
                    env = mapOf("LEYLINE_NATIVE_EXTERNAL_HOST" to "192.168.1.5:9999"),
                ).resolve(file)
            nativeEndpoints(explicitAuthority.config.native).advertisedFdUri shouldBe "192.168.1.5:9999"
        }

        test("two resolved instances use distinct listeners, player state, and artifact roots") {
            val dir = tmpDir()
            val file = writeToml(dir)
            val defaultState = File(dir, "default-state")

            val alpha =
                LeylineConfigResolver(
                    baseDir = dir,
                    env =
                        mapOf(
                            "LEYLINE_INSTANCE" to "alpha",
                            "LEYLINE_NATIVE_FD_PORT" to "31010",
                            "LEYLINE_NATIVE_MD_PORT" to "31003",
                        ),
                    defaultStateDir = defaultState,
                ).resolve(file)
            val beta =
                LeylineConfigResolver(
                    baseDir = dir,
                    env =
                        mapOf(
                            "LEYLINE_INSTANCE" to "beta",
                            "LEYLINE_NATIVE_FD_PORT" to "32010",
                            "LEYLINE_NATIVE_MD_PORT" to "32003",
                        ),
                    defaultStateDir = defaultState,
                ).resolve(file)

            assertSoftly {
                nativeEndpoints(alpha.config.native).frontDoorPort shouldBe 31010
                nativeEndpoints(beta.config.native).frontDoorPort shouldBe 32010
                alpha.paths.playerDb.absolutePath shouldBe File(defaultState, "alpha/player.db").absolutePath
                beta.paths.playerDb.absolutePath shouldBe File(defaultState, "beta/player.db").absolutePath
                alpha.paths.artifactsRoot.absolutePath shouldBe File(dir, "logs/alpha").absolutePath
                beta.paths.artifactsRoot.absolutePath shouldBe File(dir, "logs/beta").absolutePath
                alpha.paths.playerDb.absolutePath shouldNotBe beta.paths.playerDb.absolutePath
                alpha.paths.artifactsRoot.absolutePath shouldNotBe beta.paths.artifactsRoot.absolutePath
            }
        }

        test("instance state and artifacts are fully isolated from the ordinary instance") {
            val dir = tmpDir()
            val file = writeToml(dir)
            val defaultState = File(dir, "default-state")

            val ordinary = LeylineConfigResolver(baseDir = dir, env = emptyMap(), defaultStateDir = defaultState).resolve(file)
            val second =
                LeylineConfigResolver(
                    baseDir = dir,
                    env = mapOf("LEYLINE_INSTANCE" to "second"),
                    defaultStateDir = defaultState,
                ).resolve(file)

            assertSoftly {
                ordinary.paths.playerDb.absolutePath shouldBe File(defaultState, "player.db").absolutePath
                second.paths.playerDb.absolutePath shouldBe File(defaultState, "second/player.db").absolutePath
                ordinary.paths.artifactsRoot.absolutePath shouldBe File(dir, "logs").absolutePath
                second.paths.artifactsRoot.absolutePath shouldBe File(dir, "logs/second").absolutePath
            }
        }
    })
