package leyline.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.TaskAction
import java.util.zip.ZipFile

abstract class VerifyWebProfilePostureTask : DefaultTask() {
    @get:Classpath
    abstract val classpath: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val files = classpath.files
        val forbiddenEntries = listOf("leyline/native/")
        val forbiddenText = "30010"

        val leakedNames = files.filter { file -> file.nameWithoutExtension == "native" }
        check(leakedNames.isEmpty()) { "web profile includes forbidden artifacts: ${leakedNames.joinToString { it.name }}" }

        val leakedEntries =
            files.flatMap { file ->
                if (!file.isFile || file.extension != "jar") return@flatMap emptyList()
                ZipFile(file).use { zip ->
                    zip.entries().asSequence()
                        .map { it.name }
                        .filter { entry -> forbiddenEntries.any(entry::startsWith) }
                        .map { entry -> "${file.name}:$entry" }
                        .toList()
                }
            }
        check(leakedEntries.isEmpty()) { "web profile includes forbidden classes: ${leakedEntries.joinToString()}" }

        val leakedPort = files.filter { file -> file.isFile && file.readBytes().decodeToString().contains(forbiddenText) }
        check(leakedPort.isEmpty()) { "web profile includes native client port literal: ${leakedPort.joinToString { it.name }}" }
    }
}
