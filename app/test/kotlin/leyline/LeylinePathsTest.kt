package leyline

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.shouldBe

class LeylinePathsTest :
    FunSpec({

        tags(UnitTag)

        test("session artifacts live under tmp sessions root") {
            LeylinePaths.SESSION_ROOT.absolutePath shouldBe "/tmp/leyline/sessions"
            LeylinePaths.SESSION_DIR.absolutePath.shouldStartWith("/tmp/leyline/sessions/")
        }

        test("engine dumps live under tmp engine root") {
            LeylinePaths.ENGINE_DUMP.absolutePath shouldBe "/tmp/leyline/engine"
        }

        test("ensureDirectories creates both temp roots") {
            LeylinePaths.ensureDirectories()
            LeylinePaths.SESSION_DIR.isDirectory shouldBe true
            LeylinePaths.ENGINE_DUMP.isDirectory shouldBe true
        }
    })
