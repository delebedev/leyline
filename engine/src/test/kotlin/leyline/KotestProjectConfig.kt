package leyline

import io.kotest.core.config.AbstractProjectConfig
import kotlin.time.Duration.Companion.seconds

/**
 * Kotest auto-discovers this class. Global per-test timeout prevents hangs
 * (most often a session-tier bridge deadlock) from eating the whole build.
 *
 * Post AI-turn-wait perf fix, current slowest legitimate tests are ~18s
 * (BlockerDeclarationTest.human-blocks-AI-attacker). 90s = ~5× headroom for
 * loaded CI / cold JVMs, and fires before the production 120s bridge
 * deadline so hang failures surface as "test timed out at 90s" with a real
 * stack trace, not a cascade of bridge-deadline-exceeded errors.
 *
 * Note: DevCheck.strict is NOT enabled here — enabling it globally breaks
 * integration tests that use InMemoryCardRepository (sparse card DB triggers
 * grpId-miss strict failures unrelated to snapshot correctness). The
 * PlayerMapper drift check in StateMapper fires when the server runs in dev
 * mode with strict=true. PlayerMapperSnapshotTest validates buildFromSnapshot
 * directly without needing strict mode.
 */
class KotestProjectConfig : AbstractProjectConfig() {
    override val timeout = 90.seconds
}
