package leyline.tooling.simclient

import forge.util.AITimeoutTracker
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import java.util.concurrent.TimeUnit

class AiTimeoutCounterTest :
    FunSpec({
        tags(UnitTag)

        fun expiredSession(
            key: Long,
            decision: AITimeoutTracker.Decision,
        ) {
            // a zero limit expires as soon as the session ends
            AITimeoutTracker.begin(key, 0L, decision)
            AITimeoutTracker.end(key)
        }

        test("counts expired AI searches by decision while running") {
            val counter = AiTimeoutCounter().apply { start() }
            expiredSession(AITimeoutTracker.key(9001, 1), AITimeoutTracker.Decision.ATTACK)
            expiredSession(AITimeoutTracker.key(9001, 1), AITimeoutTracker.Decision.ATTACK)
            expiredSession(AITimeoutTracker.key(9001, 2), AITimeoutTracker.Decision.BLOCK)

            counter.stopAndDrain() shouldBe mapOf("attack" to 2, "block" to 1)
        }

        test("ignores searches that finish in time or after the game") {
            val counter = AiTimeoutCounter().apply { start() }
            val key = AITimeoutTracker.key(9002, 1)
            AITimeoutTracker.begin(key, TimeUnit.SECONDS.toNanos(10), AITimeoutTracker.Decision.SPELL)
            AITimeoutTracker.end(key)
            val counts = counter.stopAndDrain()
            expiredSession(key, AITimeoutTracker.Decision.SPELL)

            counts.shouldBeEmpty()
        }
    })
