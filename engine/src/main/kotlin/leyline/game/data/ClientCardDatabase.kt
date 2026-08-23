package leyline.game.data

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

/**
 * Owns discovery, validation, connection, and [CardRepository] construction
 * for the client card database — the single resolution policy shared by
 * every runtime consumer (native, web, seed-db, standalone simclient, and
 * the operator lookup recipes).
 *
 * Resolution policy:
 *  1. `LEYLINE_CARD_DB`, when set, is an authoritative override. An invalid
 *     override fails without falling back to discovery.
 *  2. Otherwise the newest *usable* database under the standard client
 *     installation location is selected, and the selected path is reported
 *     so diagnostics can show which database a run is using.
 *
 * *Usable* means the file exists, is larger than a placeholder, opens as
 * SQLite, and a card query returns rows. Test and harness consumers keep
 * their YAML-fixture repositories — this class exists only for the client
 * database, and never falls back to synthetic or fixture data.
 */
class ClientCardDatabase private constructor(
    /** Resolved, validated client database file. */
    val path: File,
) {
    /** Lazily-connected Exposed handle over [path]. */
    val database: Database = Database.connect("jdbc:sqlite:${path.absolutePath}", "org.sqlite.JDBC")

    /** A read-only [CardRepository] over the validated database. */
    fun cardRepository(): CardRepository = SqliteCardRepository(database)

    companion object {
        private const val MIN_CARD_DB_BYTES = 1_000_000L

        /** Open the client card database using the environment override or standard-location autodiscovery. */
        fun open(): ClientCardDatabase = open(overridePath = System.getenv("LEYLINE_CARD_DB"), standardLocation = ::detectArenaDownloadsDir)

        /**
         * Resolve and validate the client card database path without holding a
         * connection. Thin operator tooling (the just lookup recipes) uses this
         * to obtain one validated path for direct SQL.
         */
        fun resolveValidatedPath(): File =
            resolveValidatedPath(overridePath = System.getenv("LEYLINE_CARD_DB"), standardLocation = ::detectArenaDownloadsDir)

        internal fun open(
            overridePath: String?,
            standardLocation: () -> File?,
        ): ClientCardDatabase {
            val path = resolveValidatedPath(overridePath, standardLocation)
            println("Using client card database: ${path.absolutePath}")
            return ClientCardDatabase(path)
        }

        internal fun resolveValidatedPath(
            overridePath: String?,
            standardLocation: () -> File?,
        ): File {
            val explicit = overridePath?.takeIf { it.isNotBlank() }
            if (explicit != null) {
                // Authoritative override — an invalid override fails hard, never falls back.
                return validateUsable(File(explicit))
            }
            discoverCandidates(standardLocation)
                .firstNotNullOfOrNull { validateUsableOrNull(it) }
                ?.let { return it }
            error(
                "Card database not found. Set LEYLINE_CARD_DB or install the compatible client.\n" +
                    "  macOS: ~/Library/Application Support/com.wizards.mtga/Downloads/Raw/Raw_CardDatabase_*.mtga\n" +
                    "  Windows: C:/Program Files/Epic Games/MagicTheGathering/MTGA_Data/Downloads/Raw/Raw_CardDatabase_*.mtga",
            )
        }

        private fun discoverCandidates(standardLocation: () -> File?): List<File> {
            val rawDir = standardLocation()?.resolve("Raw") ?: return emptyList()
            if (!rawDir.isDirectory) return emptyList()
            return rawDir
                .listFiles()
                .orEmpty()
                .asSequence()
                .filter { it.name.startsWith("Raw_CardDatabase_") }
                .filter { it.name.endsWith(".mtga") || it.name.endsWith(".sqlite") }
                .filter { it.length() >= MIN_CARD_DB_BYTES }
                .sortedByDescending { it.lastModified() }
                .toList()
        }

        private fun validateUsableOrNull(file: File): File? = runCatching { validateUsable(file) }.getOrNull()

        private fun validateUsable(file: File): File {
            require(file.exists()) { "Card database not found at: ${file.path}" }
            require(file.length() >= MIN_CARD_DB_BYTES) {
                "Card database at ${file.path} is ${file.length()} bytes — too small to be a real DB.\n" +
                    "Likely an in-progress download placeholder alongside a real file in the same directory.\n" +
                    "Remove the empty file and rerun, or set LEYLINE_CARD_DB to the full DB explicitly."
            }
            val database = Database.connect("jdbc:sqlite:${file.absolutePath}", "org.sqlite.JDBC")
            try {
                transaction(database) { exec("SELECT 1") { it.next() } }
            } catch (e: Exception) {
                error("Card database at ${file.path} does not open as SQLite: ${e.message}")
            }
            val grpIds = SqliteCardRepository(database).findAllGrpIds()
            check(grpIds.isNotEmpty()) {
                "Card database at ${file.path} has no usable Cards rows. Wrong file, or schema changed."
            }
            return file
        }

        /**
         * Locate the local client Downloads directory across platforms.
         *
         * macOS: ~/Library/Application Support/com.wizards.mtga/Downloads
         * Windows: <Epic install>/MTGA_Data/Downloads (card data lives inside the install)
         */
        fun detectArenaDownloadsDir(): File? {
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
                val candidates =
                    listOf(
                        File(programFiles, "Epic Games/MagicTheGathering/MTGA_Data/Downloads"),
                        File(programFilesX86, "Epic Games/MagicTheGathering/MTGA_Data/Downloads"),
                        File(programFilesX86, "Steam/steamapps/common/MTGA/MTGA_Data/Downloads"),
                    )
                candidates.firstOrNull { it.isDirectory }?.let { return it }
            }

            return null
        }
    }
}
