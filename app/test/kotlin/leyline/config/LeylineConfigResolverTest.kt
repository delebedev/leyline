package leyline.config

import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import leyline.UnitTag
import java.io.File
import java.nio.file.Files

class LeylineConfigResolverTest :
    FunSpec({

        tags(UnitTag)

        fun tmpDir(): File = Files.createTempDirectory("leyline-config-test").toFile()

        fun writeToml(
            dir: File,
            content: String,
        ): File = File(dir, LeylineConfig.FILENAME).apply { writeText(content.trimIndent()) }

        fun resolver(
            dir: File,
            env: Map<String, String> = emptyMap(),
            defaultStateDir: File = File(dir, "default-state"),
        ): LeylineConfigResolver = LeylineConfigResolver(baseDir = dir, env = env, defaultStateDir = defaultStateDir)

        context("precedence") {
            test("typed default < TOML < environment") {
                val dir = tmpDir()
                val file =
                    writeToml(
                        dir,
                        """
                        [native]
                        fd_port = 30011

                        [engine]
                        ai_speed = 2.0
                        """,
                    )
                val resolved =
                    resolver(
                        dir,
                        env =
                            mapOf(
                                "LEYLINE_NATIVE_FD_PORT" to "30012",
                                "LEYLINE_ENGINE_AI_SPEED" to "3.0",
                            ),
                    ).resolve(file)

                assertSoftly {
                    resolved.config.native.fdPort shouldBe 30012
                    resolved.config.engine.aiSpeed shouldBe 3.0
                    resolved.provenance["native.fd_port"] shouldBe Source.ENV
                    resolved.provenance["engine.ai_speed"] shouldBe Source.ENV
                    resolved.provenance["engine.skip_mulligan"] shouldBe Source.DEFAULT
                }
            }

            test("TOML values carry TOML provenance and defaults carry DEFAULT") {
                val dir = tmpDir()
                val file = writeToml(dir, "[native]\nfd_port = 30011\n")
                val resolved = resolver(dir).resolve(file)

                assertSoftly {
                    resolved.config.native.fdPort shouldBe 30011
                    resolved.provenance["native.fd_port"] shouldBe Source.TOML
                    resolved.provenance["native.md_port"] shouldBe Source.DEFAULT
                }
            }

            test("environment override applies to a nested section") {
                val dir = tmpDir()
                val file = writeToml(dir, "[engine.draft]\npicker = \"model\"\n")
                val resolved = resolver(dir, env = mapOf("LEYLINE_ENGINE_DRAFT_PICKER" to "forge")).resolve(file)

                assertSoftly {
                    resolved.config.engine.draft.picker shouldBe "forge"
                    resolved.provenance["engine.draft.picker"] shouldBe Source.ENV
                }
            }

            test("missing config file resolves to typed defaults") {
                val dir = tmpDir()
                resolver(dir).resolve(File(dir, LeylineConfig.FILENAME)).config shouldBe LeylineConfig()
            }

            test("an empty TOML resolves to the typed defaults") {
                val dir = tmpDir()
                val file = writeToml(dir, "# nothing set\n")
                val resolved = resolver(dir).resolve(file)

                resolved.config shouldBe LeylineConfig()
            }

            test("an explicit TOML value equal to its default retains TOML provenance") {
                val dir = tmpDir()
                val file = writeToml(dir, "[native]\nfd_port = 30010\n")

                resolver(dir).resolve(file).provenance["native.fd_port"] shouldBe Source.TOML
            }
        }

        context("strict inputs") {
            test("unknown TOML key fails startup") {
                val dir = tmpDir()
                val file = writeToml(dir, "[bogus]\nx = 1\n")
                shouldThrow<ConfigException> { resolver(dir).resolve(file) }
            }

            test("unknown key inside a known section fails startup") {
                val dir = tmpDir()
                val file = writeToml(dir, "[native]\nnot_a_setting = 1\n")
                shouldThrow<ConfigException> { resolver(dir).resolve(file) }
            }

            test("malformed TOML fails startup") {
                val dir = tmpDir()
                val file = writeToml(dir, "[native\nfd_port = ")
                shouldThrow<ConfigException> { resolver(dir).resolve(file) }
            }

            test("malformed environment value fails startup") {
                val dir = tmpDir()
                val file = writeToml(dir, "")
                shouldThrow<ConfigException> {
                    resolver(dir, env = mapOf("LEYLINE_NATIVE_FD_PORT" to "abc")).resolve(file)
                }.message shouldContain "LEYLINE_NATIVE_FD_PORT"
            }

            test("non-boolean environment value for a boolean setting fails startup") {
                val dir = tmpDir()
                val file = writeToml(dir, "")
                shouldThrow<ConfigException> {
                    resolver(dir, env = mapOf("LEYLINE_ENGINE_SKIP_MULLIGAN" to "yes")).resolve(file)
                }.message shouldContain "LEYLINE_ENGINE_SKIP_MULLIGAN"
            }

            test("invalid active combination fails startup") {
                val dir = tmpDir()
                val file = writeToml(dir, "[engine]\ndie_roll_winner = 3\n")
                shouldThrow<ConfigException> { resolver(dir).resolve(file) }
            }

            test("blank environment values are ignored, not errors") {
                val dir = tmpDir()
                val file = writeToml(dir, "")
                val resolved = resolver(dir, env = mapOf("LEYLINE_NATIVE_FD_PORT" to "  ")).resolve(file)
                resolved.config.native.fdPort shouldBe 30010
            }
        }

        context("paths") {
            test("relative TOML paths resolve against the config file, not the working directory") {
                val dir = tmpDir()
                val file = writeToml(dir, "[paths]\nartifacts = \"out/logs\"\n")
                val resolved = resolver(dir).resolve(file)

                assertSoftly {
                    resolved.paths.artifactsRoot.absolutePath shouldBe File(dir, "out/logs").absolutePath
                    resolved.paths.sessionJournal.absolutePath shouldBe File(dir, "out/logs/sessions.jsonl").absolutePath
                }
            }

            test("explicit state overrides the durable user-level default") {
                val dir = tmpDir()
                val file = writeToml(dir, "[paths]\nstate = \"state-dir\"\n")
                val resolved = resolver(dir).resolve(file)

                assertSoftly {
                    resolved.paths.stateDir.absolutePath shouldBe File(dir, "state-dir").absolutePath
                    resolved.paths.playerDb.absolutePath shouldBe File(dir, "state-dir/player.db").absolutePath
                }
            }

            test("unset state uses the durable user-level default") {
                val dir = tmpDir()
                val file = writeToml(dir, "")
                val defaultState = File(dir, "default-state")
                val resolved = resolver(dir, defaultStateDir = defaultState).resolve(file)

                assertSoftly {
                    resolved.paths.stateDir.absolutePath shouldBe defaultState.absolutePath
                    resolved.paths.playerDb.absolutePath
                        .shouldEndWith("player.db")
                }
            }

            test("absolute environment path wins over TOML") {
                val dir = tmpDir()
                val file = writeToml(dir, "[paths]\nartifacts = \"logs\"\n")
                val abs = File(tmpDir(), "absolute-artifacts")
                val resolved = resolver(dir, env = mapOf("LEYLINE_PATHS_ARTIFACTS" to abs.absolutePath)).resolve(file)

                assertSoftly {
                    resolved.paths.artifactsRoot.absolutePath shouldBe abs.absolutePath
                    resolved.provenance["paths.artifacts"] shouldBe Source.ENV
                }
            }
        }

        context("instance") {
            test("named instance isolates state and artifacts beneath the configured bases") {
                val dir = tmpDir()
                val file = writeToml(dir, "")
                val defaultState = File(dir, "default-state")
                val resolved = resolver(dir, env = mapOf("LEYLINE_INSTANCE" to "second"), defaultStateDir = defaultState).resolve(file)

                assertSoftly {
                    resolved.instance shouldBe "second"
                    resolved.paths.stateDir.absolutePath shouldBe File(defaultState, "second").absolutePath
                    resolved.paths.playerDb.absolutePath shouldBe File(defaultState, "second/player.db").absolutePath
                    resolved.paths.artifactsRoot.absolutePath shouldBe File(dir, "logs/second").absolutePath
                }
            }

            test("ordinary instance keeps shared state and artifacts") {
                val dir = tmpDir()
                val file = writeToml(dir, "")
                val defaultState = File(dir, "default-state")
                val resolved = resolver(dir, env = emptyMap(), defaultStateDir = defaultState).resolve(file)

                assertSoftly {
                    resolved.instance shouldBe null
                    resolved.paths.stateDir.absolutePath shouldBe defaultState.absolutePath
                    resolved.paths.artifactsRoot.absolutePath shouldBe File(dir, "logs").absolutePath
                }
            }

            test("invalid instance name fails startup") {
                val dir = tmpDir()
                val file = writeToml(dir, "")
                shouldThrow<ConfigException> {
                    resolver(dir, env = mapOf("LEYLINE_INSTANCE" to "../escape")).resolve(file)
                }
            }
        }

        context("reporting") {
            test("report lists provenance and redacts listed paths") {
                val dir = tmpDir()
                val file =
                    writeToml(
                        dir,
                        """
                        [native]
                        fd_port = 30011
                        """,
                    )
                val resolved = resolver(dir).resolve(file)
                val report = resolved.report(head = "native")

                resolved.config.native.fdPort shouldBe 30011
                assertSoftly {
                    report shouldContain "head=native"
                    report shouldContain "native.fd_port = 30011 [TOML]"
                    report shouldContain "native.md_port = 30003 [DEFAULT]"
                    report shouldContain "native.external_host = \"localhost\" [DEFAULT]"
                    report shouldContain "player_db: "
                    report shouldContain "artifacts: "
                }
            }

            test("report derives from env overrides too") {
                val dir = tmpDir()
                val file = writeToml(dir, "")
                val resolved = resolver(dir, env = mapOf("LEYLINE_ENGINE_AI_SPEED" to "2.0")).resolve(file)

                resolved.config.engine.aiSpeed shouldBe 2.0
                resolved.report(head = "native") shouldContain "engine.ai_speed = 2.0 [ENV]"
            }
        }

        context("environment names") {
            test("canonical env names derive without collisions") {
                val leaves = SettingsSchema.leaves(LeylineConfig.serializer().descriptor)
                val envNames = leaves.map { SettingsSchema.envNameOf(it.path) }
                envNames.size shouldBe envNames.toSet().size
            }
        }
    })
