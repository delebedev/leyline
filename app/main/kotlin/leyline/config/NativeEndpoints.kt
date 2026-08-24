package leyline.config

/**
 * Pure derivation of the native head's runtime surface from resolved
 * settings. Keeps listener and advertised-endpoint consistency testable
 * without binding sockets.
 */
data class NativeEndpoints(
    val frontDoorPort: Int,
    val matchDoorPort: Int,
    val debugPort: Int,
    val accountPort: Int,
    val managementPort: Int,
    val externalHost: String,
    /** Advertised Front Door authority (`host:port`). */
    val advertisedFdUri: String,
)

fun nativeEndpoints(settings: NativeSettings): NativeEndpoints =
    NativeEndpoints(
        frontDoorPort = settings.fdPort,
        matchDoorPort = settings.mdPort,
        debugPort = settings.debugPort,
        accountPort = settings.accountPort,
        managementPort = settings.managementPort,
        externalHost = settings.externalHost,
        advertisedFdUri = advertisedFdUri(settings.externalHost, settings.fdPort),
    )

/** Advertised authority: bare hosts append the Front Door port; an explicit `host:port` value is used verbatim. */
fun advertisedFdUri(
    externalHost: String,
    fdPort: Int,
): String = if (externalHost.contains(":")) externalHost else "$externalHost:$fdPort"
