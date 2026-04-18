package leyline

import org.slf4j.LoggerFactory

/**
 * Global strict-checking guards for development.
 *
 * Initialized once at startup from [leyline.config.MatchConfig.dev]. In production mode
 * (defaults), all checks degrade to the existing warn+fallback behavior.
 * In strict mode, they throw so bugs surface immediately.
 *
 * Lives in root `leyline` package so all architectural tiers (bridge, game, match)
 * can use it without violating layering constraints.
 *
 * Two independent knobs:
 * - [strict] — data/mapping failures (missing grpId, instanceId not in map, no pending action)
 * - [strictPass] — auto-pass from missing data (bridge timeouts, prompt timeouts, auto-resolve)
 */
object DevCheck {
    private val log = LoggerFactory.getLogger(DevCheck::class.java)

    @Volatile var strict: Boolean = false
        private set

    @Volatile var strictPass: Boolean = false
        private set

    /** Initialize from config values. Call once at startup. */
    fun init(strict: Boolean, strictPass: Boolean) {
        this.strict = strict
        this.strictPass = strictPass
        if (strict || strictPass) {
            log.info("DevCheck enabled: strict={} strictPass={}", strict, strictPass)
        }
    }

    /**
     * If [value] is null and [strict] is on, throw with [message].
     * Otherwise return [value] as-is (null propagates to caller's fallback).
     */
    inline fun <T> requireOrNull(value: T?, message: () -> String): T? {
        if (value == null && strict) error("[strict] ${message()}")
        return value
    }

    /**
     * Pure guard — crash in strict mode with no return value.
     * Use at bail-out sites where the caller returns/continues after the check.
     */
    inline fun fail(message: () -> String) {
        if (strict) error("[strict] ${message()}")
    }

    /**
     * Call at auto-pass / auto-resolve sites. If [strictPass] is on, throw.
     * Otherwise the caller proceeds with its fallback behavior.
     */
    inline fun failOnAutoPass(message: () -> String) {
        if (strictPass) error("[strict-pass] ${message()}")
    }

    /**
     * Optional opt-in for the snap-diff dual-check window (arena-lab-9d8 migration).
     * When true, BundleBuilder bundles run BOTH the new buildDiffFromSnapshot and
     * the legacy buildDiffFromGame, asserting proto equality. Defaults false because
     * mutable drain semantics (drainEvents, drainDeletions) make a perfect assertion
     * fragile — Task 8's full integration suite is the production safety net.
     * Remove this field with the dual-check scaffolding once Task 11 ships.
     */
    @Volatile var snapDiffDualCheck: Boolean = false
        private set

    /** Set the snap-diff dual-check flag (used by tests that exercise the dual-path). */
    fun setSnapDiffDualCheck(enabled: Boolean) {
        this.snapDiffDualCheck = enabled
    }
}
