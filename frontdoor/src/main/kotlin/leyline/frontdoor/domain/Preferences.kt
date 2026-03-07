package leyline.frontdoor.domain

/**
 * Typed wrapper around player preferences JSON.
 * We don't parse individual preferences yet — this prevents raw
 * strings from crossing service boundaries.
 */
@JvmInline value class Preferences(val json: String)
