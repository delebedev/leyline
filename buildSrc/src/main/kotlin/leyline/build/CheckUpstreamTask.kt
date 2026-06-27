package leyline.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import java.io.File

abstract class CheckUpstreamTask : DefaultTask() {
    @get:InputFile
    @get:Optional
    abstract val stampFile: RegularFileProperty

    @get:Input
    abstract val forgeDir: Property<String>

    @get:Input
    abstract val rootDir: Property<String>

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun check() {
        checkForgeGitlink()

        val stamp = stampFile.orNull?.asFile
        if (stamp == null || !stamp.exists()) {
            throw GradleException("Upstream JARs not installed. Run: just install-forge")
        }
        val stampHash = stamp.readText().trim()
        val upstreamHash = git(
            "-C", forgeDir.get(), "log", "-1", "--format=%H", "--",
            "forge-core/src", "forge-game/src", "forge-ai/src", "forge-gui/src", "pom.xml",
        )
        if (stampHash != upstreamHash) {
            throw GradleException(
                "Upstream sources changed. Run: just install-forge\n" +
                    "  forgeDir=${forgeDir.get()}\n" +
                    "  stamp=$stampHash\n" +
                    "  actual=$upstreamHash",
            )
        }
    }

    private fun checkForgeGitlink() {
        val expected = git("-C", rootDir.get(), "ls-files", "--stage", "forge")
            .lineSequence()
            .firstOrNull()
            ?.split(Regex("\\s+"))
            ?.getOrNull(1)
            ?: throw GradleException("Forge submodule gitlink missing. Run: git submodule update --init forge")
        val actual = git("-C", forgeDir.get(), "rev-parse", "HEAD")
        if (expected != actual) {
            throw GradleException(
                "Forge submodule checkout is stale. Run: git submodule update --init forge\n" +
                    "  expected=$expected\n" +
                    "  actual=$actual",
            )
        }
    }

    private fun git(vararg args: String): String {
        val proc = ProcessBuilder("git", *args)
            .redirectErrorStream(true)
            .also {
                // A Gradle daemon inherits env from whichever shell started it;
                // if GIT_DIR / GIT_WORK_TREE are set (common in worktree shells)
                // they override `-C` and make git look at the wrong repo.
                it.environment().keys.removeIf { k -> k.startsWith("GIT_") }
            }
            .start()
        val output = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        if (proc.exitValue() != 0) {
            throw GradleException("git ${args.joinToString(" ")} failed (${proc.exitValue()}): $output")
        }
        return output
    }
}
