package leyline.frontdoor

/**
 * Static golden capture data loaded from classpath resources (`fd-golden/`).
 *
 * Formats, sets, graph definitions, etc. are read once and reused for every
 * FD connection. Player-specific data (decks, preferences) is served from
 * the repository layer; this class provides the fallback values.
 */
class GoldenData(
    val getFormatsProto: ByteArray,
    val getSetsProto: ByteArray,
    val startHookJson: String,
    val graphDefinitionsJson: String,
    val designerMetadataJson: String,
    val goldenPlayerPreferencesJson: String,
    val graphStateResponses: Map<String, String>,
) {
    companion object {
        fun loadFromClasspath(): GoldenData = GoldenData(
            getFormatsProto = loadResource("fd-golden/get-formats-response.bin"),
            getSetsProto = loadResource("fd-golden/get-sets-response.bin"),
            startHookJson = loadTextResource("fd-golden/start-hook.json"),
            graphDefinitionsJson = loadTextResource("fd-golden/graph-definitions.json"),
            designerMetadataJson = loadTextResource("fd-golden/designer-metadata.json"),
            goldenPlayerPreferencesJson = loadTextResource("fd-golden/player-preferences.json"),
            graphStateResponses = mapOf(
                "NPE_Tutorial" to loadTextResource("fd-golden/graph-state-npe-tutorial.json"),
                "NewPlayerExperience" to loadTextResource("fd-golden/graph-state-npe.json"),
                "ColorChallenge" to loadTextResource("fd-golden/graph-state-color-challenge.json"),
            ),
        )

        private fun loadResource(path: String): ByteArray =
            GoldenData::class.java.classLoader.getResourceAsStream(path)
                ?.readBytes()
                ?: error("Missing classpath resource: $path")

        private fun loadTextResource(path: String): String =
            loadResource(path).toString(Charsets.UTF_8)
    }
}
