package leyline

import leyline.account.AccountServer
import leyline.config.MatchConfig
import leyline.debug.DebugServer
import leyline.game.ExposedCardRepository
import leyline.infra.LeylineServer
import leyline.infra.ManagementServer
import java.io.File

/**
 * Standalone entry point for the local Leyline server.
 *
 * Run via justfile target: `just serve`.
 * See CLAUDE.md for mode descriptions.
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
    val cardRepo = openCardRepo(a)
    val fdPort = a["--fd-port"]?.toIntOrNull() ?: sc.fdPort
    val mdPort = a["--md-port"]?.toIntOrNull() ?: sc.mdPort
    val fdHost = a["--fd-host"]
        ?: System.getenv("LEYLINE_FD_HOST")
        ?: "localhost:$fdPort"

    val playerDbPath = System.getenv("LEYLINE_PLAYER_DB") ?: sc.playerDb.ifEmpty { LeylinePaths.PLAYER_DB.absolutePath }
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
    val envCert = System.getenv("LEYLINE_CERT_PATH")?.let { File(it) }?.takeIf { it.exists() }
    val envKey = System.getenv("LEYLINE_KEY_PATH")?.let { File(it) }?.takeIf { it.exists() }
    val cert = a["--cert"]?.let { File(it) } ?: envCert
    val key = a["--key"]?.let { File(it) } ?: envKey
    return if (cert != null && key != null) cert to key else null to null
}

private fun openCardRepo(a: Map<String, String>): ExposedCardRepository {
    val cardDbPath = System.getenv("LEYLINE_CARD_DB")
        ?: detectArenaCardDb()
    requireNotNull(cardDbPath) {
        "Card database not found. Set LEYLINE_CARD_DB or install the compatible client.\n" +
            "  macOS: ~/Library/Application Support/com.wizards.mtga/Downloads/Raw/Raw_CardDatabase_*.mtga\n" +
            "  Windows: C:/Program Files/Epic Games/MagicTheGathering/MTGA_Data/Downloads/Raw/Raw_CardDatabase_*.mtga"
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
    sessionProvider = { server.debugSink.sessionProvider?.invoke() as? leyline.match.MatchSession },
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
    val downloadsDir = detectArenaDownloadsDir() ?: return null
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
    println("Debug panel: http://localhost:$debugPort")
    println("Account:     https://localhost:$accountPort")
    println("Doorbell:    FdURI=$fdHost")
    println("Config: ${config.summary()}")
}

// -- Utilities ----------------------------------------------------------------

private fun detectArenaCardDb(): String? {
    val rawDir = detectArenaDownloadsDir()?.resolve("Raw") ?: return null
    if (!rawDir.isDirectory) return null
    return rawDir.listFiles()
        ?.filter { it.name.startsWith("Raw_CardDatabase_") && it.name.endsWith(".mtga") }
        ?.maxByOrNull { it.lastModified() }
        ?.absolutePath
}

/**
 * Locate the local client Downloads directory across platforms.
 *
 * macOS: ~/Library/Application Support/com.wizards.mtga/Downloads
 * Windows: <Epic install>/MTGA_Data/Downloads (card data lives inside the install)
 */
internal fun detectArenaDownloadsDir(): File? {
    val home = File(System.getProperty("user.home"))
    val os = System.getProperty("os.name").lowercase()

    // macOS: user-local application support
    if (os.contains("mac")) {
        val dir = home.resolve("Library/Application Support/com.wizards.mtga/Downloads")
        if (dir.isDirectory) return dir
    }

    // Windows: inside Epic Games or Steam install directory
    if (os.contains("win")) {
        val programFiles = System.getenv("PROGRAMFILES") ?: "C:/Program Files"
        val programFilesX86 = System.getenv("PROGRAMFILES(X86)") ?: "C:/Program Files (x86)"
        val candidates = listOf(
            File(programFiles, "Epic Games/MagicTheGathering/MTGA_Data/Downloads"),
            File(programFilesX86, "Epic Games/MagicTheGathering/MTGA_Data/Downloads"),
            File(programFilesX86, "Steam/steamapps/common/MTGA/MTGA_Data/Downloads"),
        )
        candidates.firstOrNull { it.isDirectory }?.let { return it }
    }

    return null
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
