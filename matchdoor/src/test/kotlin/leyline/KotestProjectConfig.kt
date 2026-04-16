package leyline

import io.kotest.core.config.AbstractProjectConfig
import kotlin.time.Duration.Companion.seconds

/**
 * Kotest auto-discovers this class. Global per-test timeout prevents hangs
 * (most often a session-tier bridge deadlock) from eating the whole build.
 *
 * Current slowest legitimate test: DiscardInteractionTest discard-as-cost at
 * ~65s. 90s leaves ~40% headroom and fires before the 120s bridge deadline
 * so hang failures surface as "test timed out at 90s" with a real stack
 * trace, not a cascade of bridge-deadline-exceeded errors.
 */
class KotestProjectConfig : AbstractProjectConfig() {
    override val timeout = 90.seconds
}
