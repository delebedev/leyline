package leyline.architecture

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

class ModuleDependencyInvariantTest :
    FunSpec({
        val root = Path.of(System.getProperty("user.dir"))

        fun projectDependencies(module: String): Set<String> {
            val buildFile =
                if (module.isEmpty()) {
                    root.resolve("build.gradle.kts")
                } else {
                    root.resolve(module).resolve("build.gradle.kts")
                }
            if (!Files.exists(buildFile)) return emptySet()
            val dependencyPattern = Regex("project\\(\":([^\"]+)\"\\)")
            return dependencyPattern.findAll(Files.readString(buildFile)).map { it.groupValues[1] }.toSet()
        }

        test("heads and the composition root share core modules without depending on each other") {
            assertSoftly {
                // :gre-proto is the generated GRE schema owner; every direct
                // generated-type consumer declares it, and heads stay peers.
                // (Root also depends on :tools:detekt-rules via detektPlugins.)
                projectDependencies("") shouldBe setOf("domain", "engine", "gre-proto", "native", "web", "tools:detekt-rules")
                projectDependencies("native") shouldBe setOf("domain", "engine", "gre-proto")
                projectDependencies("engine") shouldBe setOf("domain", "gre-proto")
                projectDependencies("web") shouldBe setOf("domain", "engine", "gre-proto")
                projectDependencies("native") shouldNotContain "web"
                projectDependencies("web") shouldNotContain "native"
            }
        }

        test("domain and gre-proto depend on no Leyline application module") {
            assertSoftly {
                projectDependencies("domain") shouldBe emptySet()
                projectDependencies("gre-proto") shouldBe emptySet()
            }
        }

        test("native remains a leaf below the composition root") {
            val modules = listOf("domain", "engine", "gre-proto", "native", "web")
            val dependents = modules.filter { projectDependencies(it).contains("native") }
            dependents shouldBe emptyList()
        }
    })
