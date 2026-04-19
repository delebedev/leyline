package leyline

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith

class LeylinePathsTest :
    FunSpec({

        tags(UnitTag)

        val sessionsProp = "leyline.sessions.root"
        val engineProp = "leyline.engine.dump"

        afterEach {
            System.clearProperty(sessionsProp)
            System.clearProperty(engineProp)
        }

        test("session artifacts live under tmp sessions root by default") {
            System.clearProperty(sessionsProp)
            LeylinePaths.SESSION_ROOT.absolutePath shouldBe "/tmp/leyline/sessions"
            LeylinePaths.SESSION_DIR.absolutePath.shouldStartWith("/tmp/leyline/sessions/")
        }

        test("engine dumps live under tmp engine root by default") {
            System.clearProperty(engineProp)
            LeylinePaths.ENGINE_DUMP.absolutePath shouldBe "/tmp/leyline/engine"
        }

        test("ensureDirectories creates both temp roots") {
            LeylinePaths.ensureDirectories()
            LeylinePaths.SESSION_DIR.isDirectory shouldBe true
            LeylinePaths.ENGINE_DUMP.isDirectory shouldBe true
        }

        test("sysprop overrides session root") {
            System.setProperty(sessionsProp, "/tmp/leyline-test-override/sessions")
            LeylinePaths.SESSION_ROOT.absolutePath shouldBe "/tmp/leyline-test-override/sessions"
            LeylinePaths.SESSION_DIR.absolutePath.shouldStartWith("/tmp/leyline-test-override/sessions/")
        }

        test("sysprop overrides engine dump") {
            System.setProperty(engineProp, "/tmp/leyline-test-override/engine")
            LeylinePaths.ENGINE_DUMP.absolutePath shouldBe "/tmp/leyline-test-override/engine"
        }
    })
