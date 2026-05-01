package leyline

import io.kotest.core.Tag

object UnitTag : Tag()

object ConformanceTag : Tag()

object IntegrationTag : Tag()

/**
 * Sim-client batch / E2E tests — synthetic GRE log generation. Slow (drives
 * full games), tagged out of the regular gate. Run via the dedicated
 * `:simclient` Gradle task or its `just` recipe.
 */
object SimClientTag : Tag()
