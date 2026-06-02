package leyline

import io.kotest.core.Tag

object UnitTag : Tag()

object BoardTag : Tag()

object IntegrationTag : Tag()

/** Puzzle-backed scripted acceptance suites. Run via `just test-acceptance`. */
object AcceptanceTag : Tag()

/**
 * Sim-client E2E tests — synthetic GRE log generation. Slow (drives full
 * games), tagged out of the regular gate. The broad deck/puzzle matrix runs
 * through the standalone `:matchdoor:simclient` tool instead of this tag.
 */
object SimClientTag : Tag()
