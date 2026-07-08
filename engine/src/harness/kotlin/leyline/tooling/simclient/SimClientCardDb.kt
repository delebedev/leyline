package leyline.tooling.simclient

import java.io.File

private const val MIN_CARD_DB_BYTES = 1_000_000L

fun resolveSimClientCardDbPath(config: SimClientConfig): String? =
    config.cardDbPath
        ?: detectArenaCardDb()

fun validateSimClientCardDbFile(path: String): File {
    val file = File(path)
    require(file.exists()) { "Card database not found at: $path" }
    require(file.length() >= MIN_CARD_DB_BYTES) {
        "Card database at $path is ${file.length()} bytes; set LEYLINE_CARD_DB or --card-db to a complete DB."
    }
    return file
}

private fun detectArenaCardDb(): String? {
    val rawDir = detectArenaDownloadsDir()?.resolve("Raw") ?: return null
    if (!rawDir.isDirectory) return null
    return rawDir
        .listFiles()
        ?.filter { it.name.startsWith("Raw_CardDatabase_") && it.name.endsWith(".mtga") }
        ?.filter { it.length() >= MIN_CARD_DB_BYTES }
        ?.maxByOrNull { it.lastModified() }
        ?.absolutePath
}

private fun detectArenaDownloadsDir(): File? {
    val home = File(System.getProperty("user.home"))
    val os = System.getProperty("os.name").lowercase()

    if (os.contains("mac")) {
        val dir = home.resolve("Library/Application Support/com.wizards.mtga/Downloads")
        if (dir.isDirectory) return dir
    }

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
