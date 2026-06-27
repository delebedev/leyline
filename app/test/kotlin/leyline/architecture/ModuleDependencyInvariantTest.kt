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
            val buildFile = root.resolve(module).resolve("build.gradle.kts")
            if (!Files.exists(buildFile)) return emptySet()
            val dependencyPattern = Regex("project\\(\":([^\"]+)\"\\)")
            return dependencyPattern.findAll(Files.readString(buildFile)).map { it.groupValues[1] }.toSet()
        }

        test("heads share core modules without depending on each other") {
            assertSoftly {
                projectDependencies("native") shouldBe setOf("domain", "engine")
                projectDependencies("engine") shouldBe setOf("domain")
                projectDependencies("web") shouldBe setOf("domain", "engine")
                projectDependencies("native") shouldNotContain "web"
                projectDependencies("web") shouldNotContain "native"
            }
        }

        test("native remains a leaf below the composition root") {
            val modules = listOf("domain", "engine", "native", "web")
            val dependents = modules.filter { projectDependencies(it).contains("native") }
            dependents shouldBe emptyList()
        }
    })
