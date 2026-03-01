package leyline.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class SyncProtoTask : DefaultTask() {
    @get:InputFile
    abstract val sedFile: RegularFileProperty

    @get:InputFile
    abstract val upstream: RegularFileProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun sync() {
        val proc = ProcessBuilder("sed", "-f", sedFile.get().asFile.absolutePath, upstream.get().asFile.absolutePath)
            .redirectErrorStream(true)
            .start()
        val result = proc.inputStream.readBytes()
        proc.waitFor()
        if (proc.exitValue() != 0) throw GradleException("sed failed: ${String(result)}")
        outputFile.get().asFile.writeBytes(result)
    }
}
