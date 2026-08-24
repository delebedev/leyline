package leyline.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URI

/**
 * Process-head settings for the native-client head: listener ports, the
 * advertised Front Door endpoint, and operator-service bindings. The active
 * head is decided by the entry point; there are no head enable flags.
 */
@Serializable
data class NativeSettings(
    /** Front Door listener port (client auth + deck management). */
    @SerialName("fd_port")
    val fdPort: Int = 30010,
    /** Match Door listener port (game protocol). */
    @SerialName("md_port")
    val mdPort: Int = 30003,
    /** Local control HTTP listener port. */
    @SerialName("debug_port")
    val debugPort: Int = 8090,
    /** Account (auth) HTTPS listener port. */
    @SerialName("account_port")
    val accountPort: Int = 9443,
    /** Management HTTP (health checks) listener port. */
    @SerialName("management_port")
    val managementPort: Int = 8091,
    /**
     * Advertised Front Door authority. A bare host advertises `host:<fd_port>`;
     * a `host:port` value is used verbatim.
     */
    @SerialName("external_host")
    val externalHost: String = "localhost",
    /** Listener bind address. */
    val bind: String = "127.0.0.1",
) {
    val advertisedFdUri: String
        get() = if (externalAuthority().port == -1) "$externalHost:$fdPort" else externalHost

    val matchDoorHost: String
        get() = externalAuthority().host

    fun validate() {
        listOf(
            "fd_port" to fdPort,
            "md_port" to mdPort,
            "debug_port" to debugPort,
            "account_port" to accountPort,
            "management_port" to managementPort,
        ).forEach { (name, port) ->
            require(port in 1..65535) { "native.$name must be in 1..65535, got $port" }
        }
        externalAuthority()
        require(bind.isNotBlank()) { "native.bind must not be blank" }
    }

    private fun externalAuthority(): URI {
        val uri = runCatching { URI("leyline://$externalHost") }.getOrNull()
        require(
            uri != null &&
                uri.host != null &&
                (uri.port == -1 || uri.port in 1..65535) &&
                uri.userInfo == null &&
                uri.path.isEmpty() &&
                uri.query == null &&
                uri.fragment == null,
        ) { "native.external_host must be a host or host:port, got '$externalHost'" }
        return uri
    }
}
