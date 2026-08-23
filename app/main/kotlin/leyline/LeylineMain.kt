package leyline

import leyline.config.MatchConfig
import leyline.debug.DebugServer
import leyline.game.data.CardRepository
import leyline.game.data.ClientCardDatabase
import leyline.infra.LeylineServer
import leyline.infra.ManagementServer
import leyline.native.account.AccountServer
import org.jetbrains.exposed.v1.jdbc.Database
import java.io.File

/**
 * Standalone entry point for the local Leyline server.
 *
 * Run via justfile target: `just serve`.
 * See AGENTS.md for mode descriptions.
 *
 * TLS: self-signed certs by default. Pass --cert/--key for explicit certs.
 *
 * Configuration layering (highest priority wins):
 *   CLI args > env vars > leyline.toml > code defaults
 */
fun main(args: Array<String>) {
    val a = parseArgs(args)

    val config = loadConfig(a)
    val sc = config.server
    val tls = resolveTls(a)
    val cardRepo = openCardRepo()
    val fdPort = a["--fd-port"]?.toIntOrNull() ?: sc.fdPort
    val mdPort = a["--md-port"]?.toIntOrNull() ?: sc.mdPort
    val fdHost =
        a["--fd-host"]
            ?: System.getenv("LEYLINE_FD_HOST")
            ?: "localhost:$fdPort"

    val playerDbFile = resolvePlayerDb(config)

    val server =
        LeylineServer(
            frontDoorPort = fdPort,
            matchDoorPort = mdPort,
            tlsFiles = tls,
            matchConfig = config,
            externalHost = fdHost.substringBefore(":"),
            cardRepo = cardRepo,
            playerDbFile = playerDbFile,
        )

    val debugPort = a["--debug-port"]?.toIntOrNull() ?: sc.debugPort
    val mgmtPort = a["--management-port"]?.toIntOrNull() ?: sc.managementPort
    val accountPort = a["--account-port"]?.toIntOrNull() ?: sc.accountPort

    val debugServer = buildDebugServer(debugPort, server)
    val mgmtServer = ManagementServer(port = mgmtPort, healthCheck = { server.isHealthy() })
    val accountDb =
        org.jetbrains.exposed.v1.jdbc.Database.connect(
            "jdbc:sqlite:${playerDbFile.absolutePath}",
            "org.sqlite.JDBC",
        )
    val accountServer = buildAccountServer(a, accountPort, tls, fdHost, accountDb)

    installShutdownHook(accountServer, debugServer, mgmtServer, server)
    startAll(server, mgmtServer, debugServer, accountServer)
    printBanner(config, mgmtPort, debugPort, accountPort, fdHost)

    Thread.currentThread().join()
}

// -- Config & resources -------------------------------------------------------

private fun loadConfig(a: Map<String, String>): MatchConfig {
    val configFile =
        a["--config"]?.let { File(it) }
            ?: File(System.getProperty("user.dir"), MatchConfig.DEFAULT_FILENAME)
    return MatchConfig.load(configFile)
}

/** Resolve the player DB file from env → config → default, coercing relative paths and ensuring the parent dir exists. */
internal fun resolvePlayerDb(config: MatchConfig): File {
    val path = System.getenv("LEYLINE_PLAYER_DB") ?: config.server.playerDb.ifEmpty { LeylinePaths.PLAYER_DB.absolutePath }
    val file = File(path).let { if (it.isAbsolute) it else File(System.getProperty("user.dir"), path) }
    file.parentFile?.mkdirs()
    return file
}

private fun resolveTls(a: Map<String, String>): Pair<File?, File?> {
    val envCert = System.getenv("LEYLINE_CERT_PATH")?.let { File(it) }?.takeIf { it.exists() }
    val envKey = System.getenv("LEYLINE_KEY_PATH")?.let { File(it) }?.takeIf { it.exists() }
    val cert = a["--cert"]?.let { File(it) } ?: envCert
    val key = a["--key"]?.let { File(it) } ?: envKey
    return if (cert != null && key != null) cert to key else null to null
}

private fun openCardRepo(): CardRepository = ClientCardDatabase.open().cardRepository()

// -- Server builders ----------------------------------------------------------

private fun buildDebugServer(
    port: Int,
    server: LeylineServer,
) = DebugServer(
    port = port,
    sessionProvider = { server.debugSink.sessionProvider?.invoke() as? leyline.match.MatchSession },
    runtimePuzzle = server.runtimePuzzle,
    cardRepositoryProvider = { server.cardRepo },
    aiDeckOverride = server.aiDeckOverride,
)

private fun buildAccountServer(
    a: Map<String, String>,
    port: Int,
    tls: Pair<File?, File?>,
    fdHost: String,
    database: org.jetbrains.exposed.v1.jdbc.Database,
): AccountServer {
    // If local manifest files already exist, surface their hashes in the
    // doorbell response so the client can reuse its local cache immediately.
    val cachedManifests = detectCachedManifests()

    return AccountServer(
        port = port,
        certFile = a["--account-cert"]?.let { File(it) } ?: tls.first,
        keyFile = a["--account-key"]?.let { File(it) } ?: tls.second,
        fdHost = fdHost,
        database = database,
        cachedManifests = cachedManifests,
    )
}

/**
 * Scan the local client Downloads dir for manifest files and return a BundleManifests JSON array.
 *
 * The client checks manifest hashes from config, then doorbell, then the
 * remote pointer file. Returning hashes here lets the client reuse local
 * downloads without waiting on the remote fallback path.
 *
 * The response shape mirrors the categories the client already expects so
 * existing local cache entries remain valid.
 */
private fun detectCachedManifests(): String? {
    val downloadsDir = ClientCardDatabase.detectArenaDownloadsDir() ?: return null
    if (!downloadsDir.isDirectory) return null

    // Manifest_<hex>.mtga → main
    // Manifest_Audio_<hex>.mtga → Audio category
    // Manifest_Localization_<hex>.mtga → Localization category
    val mainPattern = Regex("""^Manifest_([0-9a-f]+)\.mtga$""")
    val categoryPattern = Regex("""^Manifest_(Audio|Localization)_([0-9a-f]+)\.mtga$""")

    val entries = mutableListOf<String>()
    for (file in downloadsDir.listFiles() ?: return null) {
        categoryPattern.matchEntire(file.name)?.let { match ->
            val category = match.groupValues[1]
            val hash = match.groupValues[2]
            entries.add("""{"category":"$category","priority":50,"hash":"$hash"}""")
            println("Detected client manifest: ${file.name}")
        }
        mainPattern.matchEntire(file.name)?.let { match ->
            val hash = match.groupValues[1]
            entries.add("""{"category":"","priority":100,"hash":"$hash"}""")
            println("Detected client manifest: ${file.name}")
        }
    }
    if (entries.isEmpty()) return null
    return "[${entries.joinToString(",")}]"
}

// -- Lifecycle ----------------------------------------------------------------

private fun installShutdownHook(
    accountServer: AccountServer,
    debugServer: DebugServer,
    mgmtServer: ManagementServer,
    server: LeylineServer,
) {
    Runtime.getRuntime().addShutdownHook(
        Thread {
            accountServer.stop()
            debugServer.stop()
            mgmtServer.stop()
            server.stop()
        },
    )
}

private fun startAll(
    server: LeylineServer,
    mgmtServer: ManagementServer,
    debugServer: DebugServer,
    accountServer: AccountServer,
) {
    server.start()
    mgmtServer.start()
    debugServer.start()
    accountServer.start()
}

private fun printBanner(
    config: MatchConfig,
    mgmtPort: Int,
    debugPort: Int,
    accountPort: Int,
    fdHost: String,
) {
    val mode = "local"

    println("Starting Leyline server ($mode mode)...")
    println("Leyline server running. Press Ctrl+C to stop.")
    println("Management: http://localhost:$mgmtPort/health")
    println("Debug controls: http://localhost:$debugPort")
    println("Account:     https://localhost:$accountPort")
    println("Doorbell:    FdURI=$fdHost")
    println("Config: ${config.summary()}")
}

// -- Utilities ----------------------------------------------------------------

internal fun parseArgs(args: Array<String>): Map<String, String> {
    val map = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
        if (args[i].startsWith("--") && i + 1 < args.size) {
            map[args[i]] = args[i + 1]
            i += 2
        } else {
            i++
        }
    }
    return map
}
