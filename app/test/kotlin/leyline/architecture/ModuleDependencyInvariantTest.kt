package leyline.architecture

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
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

        test("doors share domain without depending on frontdoor") {
            assertSoftly {
                projectDependencies("frontdoor") shouldContainExactlyInAnyOrder listOf("domain")
                projectDependencies("matchdoor") shouldContain "domain"
                projectDependencies("matchdoor") shouldContain "engine"
                projectDependencies("engine") shouldContain "domain"
                projectDependencies("webdoor") shouldContain "domain"
                projectDependencies("webdoor") shouldContain "engine"
                projectDependencies("matchdoor") shouldNotContain "frontdoor"
                projectDependencies("webdoor") shouldNotContain "frontdoor"
            }
        }

        test("frontdoor remains a leaf below the composition root") {
            val modules = listOf("account", "domain", "engine", "frontdoor", "matchdoor", "webdoor")
            val dependents = modules.filter { projectDependencies(it).contains("frontdoor") }
            dependents shouldBe emptyList()
        }
    })
