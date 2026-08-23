package leyline

import leyline.config.ConfigException
import leyline.config.LeylineConfig
import leyline.config.LeylineConfigResolver
import leyline.config.NativeEndpoints
import leyline.config.ResolvedLeylineConfig
import leyline.config.nativeEndpoints
import leyline.debug.DebugServer
import leyline.game.data.CardRepository
import leyline.game.data.ClientCardDatabase
import leyline.infra.LeylineServer
import leyline.infra.ManagementServer
import leyline.native.account.AccountServer
import java.io.File

/**
 * Standalone entry point for the local Leyline server (native head).
 *
 * Run via justfile target: `just serve`.
 * See AGENTS.md for mode descriptions.
 *
 * Configuration is resolved once from the fixed `leyline.toml` plus `LEYLINE_*`
 * environment overrides; see [LeylineConfigResolver]. `--cert`/`--key` still
 * select explicit TLS certificates.
 */
fun main(args: Array<String>) {
    val a = parseArgs(args)

    val resolved = resolveLaunchConfig()
    val native = resolved.config.native
    val paths = resolved.paths.also { it.ensureDirectories() }
    val endpoints = nativeEndpoints(native)
    // File logging lands beneath the resolved per-instance artifact root.
    System.setProperty("LEYLINE_LOG_DIR", paths.artifactsRoot.absolutePath)

    val tls = resolveTls(a)
    val cardRepo = openCardRepo()
    val server =
        LeylineServer(
            frontDoorPort = endpoints.frontDoorPort,
            matchDoorPort = endpoints.matchDoorPort,
            tlsFiles = tls,
            engineSettings = resolved.config.engine,
            puzzlesDir = paths.puzzlesDir,
            draftModelDir = paths.draftModelDir(resolved.config.engine.draft.modelDir),
            externalHost = native.externalHost,
            cardRepo = cardRepo,
            playerDbFile = paths.playerDb,
            engineDumpDir = paths.engineDump,
        )

    val debugServer = buildDebugServer(native.debugPort, native.debugBind, server)
    val mgmtServer = ManagementServer(port = endpoints.managementPort, healthCheck = { server.isHealthy() })
    val accountDb =
        org.jetbrains.exposed.v1.jdbc.Database.connect(
            "jdbc:sqlite:${paths.playerDb.absolutePath}",
            "org.sqlite.JDBC",
        )
    val accountServer = buildAccountServer(a, endpoints.accountPort, tls, endpoints.advertisedFdUri, accountDb)

    installShutdownHook(accountServer, debugServer, mgmtServer, server)
    startAll(server, mgmtServer, debugServer, accountServer)
    printBanner(resolved, endpoints)

    Thread.currentThread().join()
}

// -- Config & resources -------------------------------------------------------

private fun resolveLaunchConfig(): ResolvedLeylineConfig =
    try {
        LeylineConfigResolver(baseDir = File(System.getProperty("user.dir")), env = System.getenv()).resolve()
    } catch (e: ConfigException) {
        System.err.println("Configuration error: ${e.message}")
        kotlin.system.exitProcess(1)
    }

private fun resolveTls(a: Map<String, String>): Pair<File?, File?> {
    val envCert = System.getenv("LEYLINE_CERT_PATH")?.let { File(it) }?.takeIf { it.exists() }
    val envKey = System.getenv("LEYLINE_KEY_PATH")?.let { File(it) }?.takeIf { it.exists() }
    val cert = a["--cert"]?.let { File(it) } ?: envCert
    val key = a["--key"]?.let { File(it) } ?: envKey
    return if (cert != null && key != null) cert to key else null to null
}

private fun openCardRepo(): CardRepository = ClientCardDatabase.open(overridePath = System.getenv("LEYLINE_CARD_DB")).cardRepository()

// -- Server builders ----------------------------------------------------------

private fun buildDebugServer(
    port: Int,
    bindAddress: String,
    server: LeylineServer,
) = DebugServer(
    port = port,
    bindAddress = bindAddress,
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
    resolved: ResolvedLeylineConfig,
    endpoints: NativeEndpoints,
) {
    println(resolved.report(head = "native", redactedPaths = LeylineConfig.SECRET_PATHS))
    println("Leyline server running. Press Ctrl+C to stop.")
    println("Management: http://localhost:${endpoints.managementPort}/health")
    println("Debug controls: http://localhost:${endpoints.debugPort}")
    println("Account:     https://localhost:${endpoints.accountPort}")
    println("Doorbell:    FdURI=${endpoints.advertisedFdUri}")
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
