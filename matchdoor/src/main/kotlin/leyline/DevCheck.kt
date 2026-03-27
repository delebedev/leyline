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
     * Call at auto-pass / auto-resolve sites. If [strictPass] is on, throw.
     * Otherwise the caller proceeds with its fallback behavior.
     */
    inline fun failOnAutoPass(message: () -> String) {
        if (strictPass) error("[strict-pass] ${message()}")
    }
}
