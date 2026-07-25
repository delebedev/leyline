package io.kotest.provided

import io.kotest.core.config.AbstractProjectConfig
import kotlin.time.Duration.Companion.seconds

/**
 * Global engine-test timeout. Most legitimate tests finish within seconds;
 * this leaves CI headroom while failing before the production bridge deadline.
 *
 * DevCheck.strict stays disabled because integration fixtures intentionally use
 * sparse card repositories. PlayerMapperSnapshotTest covers snapshot drift
 * directly, while server dev mode owns runtime strict checks.
 */
class ProjectConfig : AbstractProjectConfig() {
    override val timeout = 90.seconds
}
