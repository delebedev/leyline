package leyline

import io.kotest.core.Tag

object UnitTag : Tag()

object BoardTag : Tag()

object IntegrationTag : Tag()

/** Puzzle-backed scripted acceptance suites. Run via `just test-acceptance`. */
object AcceptanceTag : Tag()

/**
 * Sim-client E2E tests — synthetic GRE log generation. Slow (drives full
 * games), tagged out of the regular gate. Tool wiring is covered by
 * `:engine:simclientSmoke`; broad deck/puzzle matrices run through the
 * standalone `:engine:simclient` tool instead of this tag.
 */
object SimClientTag : Tag()
