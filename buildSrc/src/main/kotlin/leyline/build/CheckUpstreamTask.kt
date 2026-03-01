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

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun check() {
        val stamp = stampFile.orNull?.asFile
        if (stamp == null || !stamp.exists()) {
            throw GradleException("Upstream JARs not installed. Run: just install-forge")
        }
        val stampHash = stamp.readText().trim()
        val proc = ProcessBuilder(
            "git", "log", "-1", "--format=%H", "--",
            "forge-core/src", "forge-game/src", "forge-ai/src", "forge-gui/src", "pom.xml",
        )
            .directory(File(forgeDir.get()))
            .start()
        val upstreamHash = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        if (stampHash != upstreamHash) {
            throw GradleException("Upstream sources changed. Run: just install-forge")
        }
    }
}
