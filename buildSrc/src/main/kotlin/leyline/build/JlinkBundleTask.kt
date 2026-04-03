package leyline.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Builds a self-contained leyline distribution:
 * - jlink custom JRE (stripped to required modules, no java.desktop)
 * - Platform-stripped sqlite-jdbc (single native lib instead of 23)
 * - Launch script
 *
 * Input: `installDist` output directory.
 * Output: `build/bundle/` ready for archiving.
 */
abstract class JlinkBundleTask : DefaultTask() {

    /** The installDist output (contains lib/ with all JARs). */
    @get:InputDirectory
    abstract val installDir: DirectoryProperty

    @get:Input
    abstract val jlinkModules: Property<String>

    /** Native platform path inside sqlite-jdbc, e.g. "Mac/aarch64". */
    @get:Input
    abstract val sqlitePlatform: Property<String>

    @get:Input
    abstract val jvmArgs: ListProperty<String>

    @get:Input
    abstract val mainClass: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun bundle() {
        val out = outputDir.get().asFile
        if (out.exists()) out.deleteRecursively()
        out.mkdirs()

        val jreDir = File(out, "jre")
        val libDir = File(out, "lib")
        libDir.mkdirs()

        buildJlinkRuntime(jreDir)
        copyAndStripLibs(libDir)
        writeLaunchScript(out)

        val jreSize = dirSize(jreDir)
        val libSize = dirSize(libDir)
        logger.lifecycle(
            "Bundle: jre=${jreSize / MB}MB lib=${libSize / MB}MB total=${(jreSize + libSize) / MB}MB",
        )
    }

    private fun buildJlinkRuntime(jreDir: File) {
        val javaHome = System.getProperty("java.home")
        val jlink = listOf("$javaHome/bin/jlink", "$javaHome/bin/jlink.exe", "$javaHome/../bin/jlink", "$javaHome/../bin/jlink.exe")
            .map(::File).firstOrNull { it.exists() }
            ?: error("jlink not found — need a JDK (not JRE)")

        // JDK 21+ uses "zip-6", JDK 17 uses "2" (max compression)
        val javaVersion = Runtime.version().feature()
        val compress = if (javaVersion >= 21) "zip-6" else "2"

        logger.lifecycle("jlink: modules=${jlinkModules.get()} compress=$compress (JDK $javaVersion)")
        val proc = ProcessBuilder(
            jlink.absolutePath,
            "--add-modules", jlinkModules.get(),
            "--output", jreDir.absolutePath,
            "--strip-debug",
            "--compress", compress,
            "--no-header-files",
            "--no-man-pages",
        ).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        val exit = proc.waitFor()
        if (output.isNotBlank()) logger.lifecycle("jlink output: $output")
        require(exit == 0) { "jlink failed with exit code $exit\n$output" }
    }

    private fun copyAndStripLibs(libDir: File) {
        val platform = sqlitePlatform.get()
        val sourceLib = File(installDir.get().asFile, "lib")
        sourceLib.listFiles { f -> f.extension == "jar" }?.forEach { jar ->
            val dest = File(libDir, jar.name)
            if (jar.name.startsWith("sqlite-jdbc")) {
                stripSqliteJdbc(jar, dest, platform)
            } else {
                jar.copyTo(dest)
            }
        }
    }

    /** Repack sqlite-jdbc keeping only the native lib for [platform]. */
    private fun stripSqliteJdbc(source: File, dest: File, platform: String) {
        val originalSize = source.length()
        ZipFile(source).use { zip ->
            ZipOutputStream(dest.outputStream()).use { zos ->
                zip.entries().asSequence().forEach { entry ->
                    val isNative = entry.name.contains("/native/")
                    val isTargetPlatform = entry.name.contains("/native/$platform/")
                    if (!isNative || isTargetPlatform) {
                        zos.putNextEntry(ZipEntry(entry.name))
                        if (!entry.isDirectory) {
                            zip.getInputStream(entry).copyTo(zos)
                        }
                        zos.closeEntry()
                    }
                }
            }
        }
        logger.lifecycle("sqlite-jdbc: ${originalSize / KB}KB → ${dest.length() / KB}KB ($platform)")
    }

    private fun writeLaunchScript(bundleDir: File) {
        val args = jvmArgs.get().joinToString(" ")
        val main = mainClass.get()
        val binDir = File(bundleDir, "bin")
        binDir.mkdirs()

        // Unix shell script
        val script = File(binDir, "leyline")
        script.writeText(
            buildString {
                appendLine("#!/bin/sh")
                appendLine("DIR=\"$(cd \"$(dirname \"$0\")/..\" && pwd)\"")
                appendLine("exec \"${'$'}DIR/jre/bin/java\" \\")
                appendLine("  $args \\")
                appendLine("  -Dleyline.res.dir=\"${'$'}DIR/res\" \\")
                appendLine("  -cp \"${'$'}DIR/lib/*\" \\")
                appendLine("  $main \"${'$'}@\"")
            },
        )
        script.setExecutable(true)

        // Windows batch script
        val bat = File(binDir, "leyline.bat")
        bat.writeText(
            buildString {
                appendLine("@echo off")
                appendLine("set DIR=%~dp0..")
                appendLine("\"%DIR%\\jre\\bin\\java\" ^")
                appendLine("  $args ^")
                appendLine("  -Dleyline.res.dir=\"%DIR%\\res\" ^")
                appendLine("  -cp \"%DIR%\\lib\\*\" ^")
                appendLine("  $main %*")
                appendLine("exit /b %ERRORLEVEL%")
            },
        )
    }

    private fun dirSize(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    companion object {
        private const val MB = 1_048_576L
        private const val KB = 1024L
    }
}
