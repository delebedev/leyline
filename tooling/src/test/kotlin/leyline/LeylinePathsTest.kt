package leyline

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files

class LeylinePathsTest :
    FunSpec({

        tags(UnitTag)

        test("createLatestSymlink creates relative symlink") {
            val tempDir = Files.createTempDirectory("leyline-paths-test").toFile()
            try {
                val sessionDir = File(tempDir, "2026-03-22_12-00-00")
                sessionDir.mkdirs()

                LeylinePaths.createLatestSymlink(tempDir, "2026-03-22_12-00-00")

                val link = File(tempDir, "latest").toPath()
                Files.isSymbolicLink(link) shouldBe true

                val target = Files.readSymbolicLink(link)
                target.isAbsolute shouldBe false
                target.toString() shouldBe "2026-03-22_12-00-00"
            } finally {
                tempDir.deleteRecursively()
            }
        }

        test("createLatestSymlink replaces stale directory with symlink") {
            val tempDir = Files.createTempDirectory("leyline-paths-stale").toFile()
            try {
                // Create a stale "latest" directory (the bug scenario)
                val staleDir = File(tempDir, "latest")
                staleDir.mkdirs()
                File(staleDir, "client-errors.jsonl").createNewFile()

                val sessionDir = File(tempDir, "2026-03-22_13-00-00")
                sessionDir.mkdirs()

                LeylinePaths.createLatestSymlink(tempDir, "2026-03-22_13-00-00")

                val link = File(tempDir, "latest").toPath()
                Files.isSymbolicLink(link) shouldBe true
                Files.readSymbolicLink(link).toString() shouldBe "2026-03-22_13-00-00"
            } finally {
                tempDir.deleteRecursively()
            }
        }

        test("createLatestSymlink replaces old symlink with new target") {
            val tempDir = Files.createTempDirectory("leyline-paths-replace").toFile()
            try {
                File(tempDir, "old-session").mkdirs()
                File(tempDir, "new-session").mkdirs()

                LeylinePaths.createLatestSymlink(tempDir, "old-session")
                LeylinePaths.createLatestSymlink(tempDir, "new-session")

                val target = Files.readSymbolicLink(File(tempDir, "latest").toPath())
                target.toString() shouldBe "new-session"
            } finally {
                tempDir.deleteRecursively()
            }
        }
    })
