package leyline.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    /** Local control listener bind address (loopback by default). */
    @SerialName("debug_bind")
    val debugBind: String = "127.0.0.1",
) {
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
        require(externalHost.isNotBlank()) { "native.external_host must not be blank" }
        require(debugBind.isNotBlank()) { "native.debug_bind must not be blank" }
    }
}
