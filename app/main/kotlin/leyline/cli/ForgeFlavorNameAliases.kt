package leyline.cli

import java.io.File

internal object ForgeFlavorNameAliases {
    fun load(projectDir: File): Map<String, String> {
        val cardsDir = projectDir.resolve("forge/forge-gui/res/cardsfolder")
        if (!cardsDir.isDirectory) return emptyMap()

        return cardsDir
            .walkTopDown()
            .filter { it.isFile && it.extension == "txt" }
            .flatMap { file -> parseFile(file).asSequence().map { it.toPair() } }
            .toMap()
    }

    internal fun parseFile(file: File): Map<String, String> {
        val aliases = linkedMapOf<String, String>()
        var currentName: String? = null

        file.forEachLine { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("Name:") -> {
                    currentName = line.removePrefix("Name:").trim().takeIf { it.isNotEmpty() }
                }

                line.startsWith("Variant:") && line.contains(FLAVOR_NAME_MARKER) -> {
                    val deckName = currentName ?: return@forEachLine
                    val dbName = line.substringAfter(FLAVOR_NAME_MARKER).trim()
                    if (dbName.isNotEmpty() && dbName != deckName) aliases.putIfAbsent(deckName, dbName)
                }
            }
        }

        return aliases
    }

    private const val FLAVOR_NAME_MARKER = "FlavorName:"
}

/**
 * Normalizes external decklist names before they become persisted grpIds.
 *
 * [findByName] and [findByNameAndSet] remain exact client card-database
 * lookups. Flavor-name aliases are import fallback only: downloaded decklists
 * can use alternate display names, while the local card database may expose the
 * corresponding gameplay name. Keeping that translation here prevents generic
 * card lookup from returning a different name/set than the caller requested.
 */
internal class ImportedCardNameResolver(
    private val findByName: (String) -> Int?,
    private val findByNameAndSet: (String, String) -> Int?,
    private val flavorNameAliases: Map<String, String>,
) {
    fun resolve(
        name: String,
        setCode: String?,
    ): Int? =
        resolveDirect(name, setCode)
            ?: flavorNameAliases[name]?.let { alias ->
                resolveDirect(alias, setCode)
            }

    private fun resolveDirect(
        name: String,
        setCode: String?,
    ): Int? =
        if (setCode != null) {
            findByNameAndSet(name, setCode) ?: findByName(name)
        } else {
            findByName(name)
        }
}
