package leyline.conformance

import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import kotlin.time.Duration.Companion.seconds

/**
 * Repro for leyline-6vtg.
 *
 * When two simultaneous triggers from distinct sources fire under one
 * controller, Forge's `PCHuman.orderSimultaneousSa` routes through
 * `getGui().order(...)` → [leyline.bridge.forge.ClientGuiGame.order]. The
 * protocol does not surface a stepper for trigger ordering — the engine
 * resolves with APNAP / first-controller-orders default.
 *
 * With auto-resolve, the chooser short-circuits before any bridge call. This
 * test pins that contract: between the cast snapshot and post-target-resolve,
 * no SelectNReq leaks from a trigger-order chooser. The deeper hang the bug
 * report describes (bridge teardown after the chooser future eventually
 * defaults) only surfaces under longer AI-driven turn flows that aren't
 * deterministic enough to assert here — the contract pin + a real playtest
 * are the regression surface for this fix.
 *
 * Setup: Festival Crasher + Monastery Swiftspear (Prowess) on the battlefield,
 * Lightning Bolt in hand. Casting Bolt fires both pump triggers simultaneously
 * with distinct SA `toString()` values, which forces `orderSimultaneousSa` to
 * invoke `getGui().order` (verified via stderr instrumentation while
 * developing the fix; instrumentation has been removed).
 */
class SimultaneousTriggerOrderTest :
    SessionTest({

        timeout = 30.seconds.inWholeMilliseconds

        test("simultaneous triggers auto-resolve — no choose_one wire prompt") {
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                removesummoningsickness=true

                humanbattlefield=Festival Crasher;Monastery Swiftspear;Mountain;Mountain;Mountain
                humanhand=Lightning Bolt
                humanlibrary=Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain
                ailibrary=Plains;Plains;Plains;Plains;Plains;Plains;Plains;Plains;Plains;Plains;Plains;Plains
                """.trimIndent(),
            )

            val snapshot = messageSnapshot()
            // [`castSpellByName`] + [`selectTargets`] drive Bolt to resolution.
            // Pump triggers fire and play in default order via the auto-resolve
            // short-circuit in `ClientGuiGame.order`.
            castSpellByName("Lightning Bolt")
            selectTargets(listOf(OPPONENT_SEAT))

            // Bolt + the two pump triggers resolve inside the cast/target flow's
            // internal drainSink. AI takes 3.
            ai.life shouldBe 17

            // Auto-resolve contract: only the SelectTargetsReq for Bolt's
            // target should appear between snapshot and end. Without the fix,
            // an additional SelectNReq from `bridge.requestChoice("choose_one")`
            // leaks onto the wire when `orderSimultaneousSa` invokes the
            // chooser through the bridge.
            val newMessages = messagesSince(snapshot)
            val selectNCount = newMessages.count { it.hasSelectNReq() }
            selectNCount shouldBe 0
        }
    })
