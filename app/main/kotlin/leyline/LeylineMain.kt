package leyline

import leyline.account.AccountServer
import leyline.config.MatchConfig
import leyline.debug.DebugServer
import leyline.game.ExposedCardRepository
import leyline.infra.LeylineServer
import leyline.infra.ManagementServer
import java.io.File

/**
 * Standalone entry point for the Leyline server (client compat layer).
 *
 * Run via justfile target: `just serve`.
 * See CLAUDE.md for mode descriptions.
 *
 * TLS: self-signed certs by default (needs mitmproxy CA certs for UnityTls validation).
 * AccountServer always self-signs (CheckSC=0 covers HTTP).
 *
 * Configuration layering (highest priority wins):
 *   CLI args > env vars > leyline.toml > code defaults
 */
fun main(args: Array<String>) {
    val a = parseArgs(args)

    val config = loadConfig(a)
    val sc = config.server
    val tls = resolveTls(a)
    val cardRepo = openCardRepo(a)
    val fdPort = a["--fd-port"]?.toIntOrNull() ?: sc.fdPort
    val mdPort = a["--md-port"]?.toIntOrNull() ?: sc.mdPort
    val fdHost = a["--fd-host"]
        ?: System.getenv("LEYLINE_FD_HOST")
        ?: "localhost:$fdPort"

    val playerDbPath = System.getenv("LEYLINE_PLAYER_DB") ?: sc.playerDb
    val playerDbFile = File(playerDbPath).let { if (it.isAbsolute) it else File(System.getProperty("user.dir"), playerDbPath) }

    val server = LeylineServer(
        frontDoorPort = fdPort,
        matchDoorPort = mdPort,
        tlsFiles = tls,
        matchConfig = config,
        externalHost = fdHost.substringBefore(":"),
        cardRepo = cardRepo,
        playerDbFile = playerDbFile,
    )

    val debugPort = a["--debug-port"]?.toIntOrNull() ?: sc.debugPort
    val mgmtPort = sc.managementPort
    val accountPort = a["--account-port"]?.toIntOrNull() ?: sc.accountPort

    val debugServer = buildDebugServer(debugPort, server)
    val mgmtServer = ManagementServer(port = mgmtPort, healthCheck = { server.isHealthy() })
    val accountDb = org.jetbrains.exposed.v1.jdbc.Database.connect(
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
    val configFile = a["--config"]?.let { File(it) }
        ?: File(System.getProperty("user.dir"), MatchConfig.DEFAULT_FILENAME)
    return MatchConfig.load(configFile)
}

private fun resolveTls(a: Map<String, String>): Pair<File?, File?> {
    // Explicit CLI/env args take priority
    val envCert = System.getenv("LEYLINE_CERT_PATH")?.let { File(it) }?.takeIf { it.exists() }
    val envKey = System.getenv("LEYLINE_KEY_PATH")?.let { File(it) }?.takeIf { it.exists() }
    val cert = a["--cert"]?.let { File(it) } ?: envCert
    val key = a["--key"]?.let { File(it) } ?: envKey
    if (cert != null && key != null) return cert to key

    // Auto-generate (or reuse existing) mitmproxy-signed certs
    val certsDir = File(
        System.getenv("LEYLINE_CERTS")
            ?: "${System.getProperty("user.home")}/.local/share/leyline/certs",
    )
    return leyline.infra.TlsHelper.ensureCerts(certsDir) ?: (null to null)
}

private fun openCardRepo(a: Map<String, String>): ExposedCardRepository {
    val cardDbPath = System.getenv("LEYLINE_CARD_DB")
        ?: detectArenaCardDb()
    requireNotNull(cardDbPath) {
        "Card database not found. Set LEYLINE_CARD_DB or install Arena client.\n" +
            "  Expected: ~/Library/Application Support/com.wizards.mtga/Downloads/Raw/Raw_CardDatabase_*.mtga"
    }
    require(File(cardDbPath).exists()) { "Card database not found at: $cardDbPath" }
    return ExposedCardRepository(
        org.jetbrains.exposed.v1.jdbc.Database.connect(
            "jdbc:sqlite:${File(cardDbPath).absolutePath}",
            "org.sqlite.JDBC",
        ),
    )
}

// -- Server builders ----------------------------------------------------------

private fun buildDebugServer(port: Int, server: LeylineServer) = DebugServer(
    port = port,
    debugCollector = server.debugCollector,
    gameStateCollector = server.gameStateCollector,
    fdCollector = server.fdCollector,
    eventBus = server.eventBus,
    runtimePuzzle = server.runtimePuzzle,
)

private fun buildAccountServer(
    a: Map<String, String>,
    port: Int,
    tls: Pair<File?, File?>,
    fdHost: String,
    database: org.jetbrains.exposed.v1.jdbc.Database,
): AccountServer {
    // Detect installed Arena manifest hash from local Downloads dir.
    // The client checks doorbell BundleManifests before the pointer file at
    // assets.mtgarena.wizards.com. If we return the hash here, the client
    // finds the manifest locally and never hits the network — enabling offline mode.
    val cachedManifests = detectArenaManifestHash()

    val debugRoles = System.getenv("LEYLINE_DEBUG").let { it == "true" || it == "1" }
    return AccountServer(
        port = port,
        roles = if (debugRoles) leyline.account.TokenService.DEBUG_ROLES else leyline.account.TokenService.DEFAULT_ROLES,
        certFile = a["--account-cert"]?.let { File(it) } ?: tls.first,
        keyFile = a["--account-key"]?.let { File(it) } ?: tls.second,
        fdHost = fdHost,
        database = database,
        cachedManifests = cachedManifests,
    )
}

/**
 * Scan Arena's local Downloads dir for the manifest file and return a BundleManifests JSON array.
 *
 * The client checks three manifest sources in order: config, doorbell, pointer file
 * (assets.mtgarena.wizards.com). Offline, the pointer file times out (~30s on boot).
 * Returning the hash via doorbell lets the client find the manifest locally and skip the download,
 * but the pointer file timeout is unavoidable — the client always checks all three sources.
 */
private fun detectArenaManifestHash(): String? {
    val downloadsDir = File(System.getProperty("user.home"), "Library/Application Support/com.wizards.mtga/Downloads")
    if (!downloadsDir.isDirectory) return null
    // Match only the main manifest (Manifest_<hex>.mtga), not category-prefixed ones
    // like Manifest_Audio_<hex>.mtga or Manifest_Localization_<hex>.mtga
    val hashPattern = Regex("""^Manifest_([0-9a-f]+)\.mtga$""")
    val manifestFile = downloadsDir.listFiles()
        ?.filter { hashPattern.matches(it.name) }
        ?.maxByOrNull { it.lastModified() }
        ?: return null
    // Extract hash from "Manifest_<hash>.mtga"
    val hash = manifestFile.name.removePrefix("Manifest_").removeSuffix(".mtga")
    if (hash.isBlank()) return null
    println("Detected Arena manifest: ${manifestFile.name}")
    return """[{"category":"","priority":100,"hash":"$hash"}]"""
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
    println("Debug panel: http://localhost:$debugPort")
    println("Account:     https://localhost:$accountPort")
    println("Doorbell:    FdURI=$fdHost")
    println("Config: ${config.summary()}")
}

// -- Utilities ----------------------------------------------------------------

private fun detectArenaCardDb(): String? {
    val rawDir = File(System.getProperty("user.home"), "Library/Application Support/com.wizards.mtga/Downloads/Raw")
    if (!rawDir.isDirectory) return null
    return rawDir.listFiles()
        ?.filter { it.name.startsWith("Raw_CardDatabase_") && it.name.endsWith(".mtga") }
        ?.maxByOrNull { it.lastModified() }
        ?.absolutePath
}

private fun parseArgs(args: Array<String>): Map<String, String> {
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
