package leyline.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class WriteClasspathTask : DefaultTask() {
    @get:Input
    abstract val classpath: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun write() {
        val out = outputFile.get().asFile
        out.parentFile.mkdirs()
        out.writeText(classpath.get())
    }
}
