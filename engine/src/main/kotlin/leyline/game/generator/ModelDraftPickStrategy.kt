package leyline.game.generator

import forge.gamemodes.limited.DefaultDraftPickStrategy
import forge.gamemodes.limited.DraftPickContext
import forge.gamemodes.limited.DraftPickStrategy
import forge.item.PaperCard
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.slf4j.LoggerFactory
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.sqrt

object DraftPickStrategies {
    private val log = LoggerFactory.getLogger(DraftPickStrategies::class.java)

    fun default(): DraftPickStrategy = DefaultDraftPickStrategy()

    fun modelBacked(
        setCode: String,
        modelDir: String,
    ): DraftPickStrategy {
        if (modelDir.isBlank()) {
            log.warn("Draft model picker requested for {} but draft.model_dir is empty; using Forge picker", setCode)
            return default()
        }
        val setDir = File(modelDir, setCode.lowercase())
        val weights = resolveWeightsFile(setDir)
        val meta = File(setDir, "card_meta.json")
        return try {
            ModelDraftPickStrategy(PickModelBundle.load(weights, meta), default())
        } catch (e: Exception) {
            log.warn("Draft model picker unavailable for {}; using Forge picker: {}", setCode, e.message)
            default()
        }
    }

    private fun resolveWeightsFile(setDir: File): File {
        val raw = File(setDir, "weights.json")
        if (raw.isFile) return raw
        return File(setDir, "weights.json.gz")
    }
}

class ModelDraftPickStrategy(
    private val model: PickModelBundle,
    private val fallback: DraftPickStrategy = DefaultDraftPickStrategy(),
) : DraftPickStrategy {
    private val log = LoggerFactory.getLogger(ModelDraftPickStrategy::class.java)

    override fun choose(context: DraftPickContext): PaperCard =
        try {
            chooseWithModel(context)
        } catch (e: Exception) {
            log.warn("Draft model picker failed; using Forge picker: {}", e.message)
            fallback.choose(context)
        }

    private fun chooseWithModel(context: DraftPickContext): PaperCard {
        val pack = DoubleArray(model.nCards)
        val pool = DoubleArray(model.nCards)
        var recognizedPackCards = 0
        for (card in context.pack) {
            val idx = model.nameToIdx[card.name]
            if (idx != null) {
                pack[idx] = 1.0
                recognizedPackCards++
            }
        }
        require(recognizedPackCards > 0) { "no cards in pack were present in model metadata" }

        for (card in context.pool) {
            val idx = model.nameToIdx[card.name]
            if (idx != null) pool[idx] += 1.0
        }

        val logits = model.forward(pack, pool, context.pickNumber)
        val best =
            context.pack
                .mapNotNull { card -> model.nameToIdx[card.name]?.let { idx -> card to logits[idx] } }
                .maxByOrNull { it.second }
                ?.first
        require(best != null) { "model produced no score for pack cards" }
        return best
    }
}

class PickModelBundle(
    val nCards: Int,
    val pickNumberEmbeddings: List<DoubleArray>,
    val layers: List<ModelLayer>,
    val nameToIdx: Map<String, Int>,
) {
    fun forward(
        pack: DoubleArray,
        pool: DoubleArray,
        pickNumber: Int,
    ): DoubleArray {
        require(pack.size == nCards && pool.size == nCards) { "input size does not match model n_cards" }
        val emb = pickNumberEmbeddings[pickNumber.coerceIn(0, pickNumberEmbeddings.lastIndex)]
        var x = DoubleArray(nCards * 2 + emb.size)
        pack.copyInto(x, destinationOffset = 0)
        pool.copyInto(x, destinationOffset = nCards)
        emb.copyInto(x, destinationOffset = nCards * 2)
        for (layer in layers) {
            x = layer.forward(x)
        }
        require(x.size == nCards) { "model output size ${x.size} does not match n_cards $nCards" }
        return x
    }

    companion object {
        private val json =
            Json {
                ignoreUnknownKeys = true
            }

        fun load(
            weightsFile: File,
            metaFile: File,
        ): PickModelBundle {
            require(weightsFile.isFile) { "missing ${weightsFile.name}" }
            require(metaFile.isFile) { "missing ${metaFile.name}" }
            val weights = json.decodeFromString(serializer<WeightsJson>(), weightsFile.readMaybeGzipText())
            val meta = json.decodeFromString(serializer<CardMetaJson>(), metaFile.readText())
            require(weights.nCards == meta.nCards) {
                "weights n_cards=${weights.nCards} differs from metadata n_cards=${meta.nCards}"
            }
            validateMeta(meta)
            validateLayers(weights)
            val layers = weights.mlp.map { raw -> raw.toLayer() }
            val nameToIdx = meta.cards.associate { it.name to it.idx }
            return PickModelBundle(
                nCards = weights.nCards,
                pickNumberEmbeddings = weights.pickNumberEmbeddings.map { it.toDoubleArray() },
                layers = layers,
                nameToIdx = nameToIdx,
            )
        }

        private fun validateMeta(meta: CardMetaJson) {
            require(meta.cards.size == meta.nCards) {
                "metadata has ${meta.cards.size} cards but n_cards=${meta.nCards}"
            }
            require(
                meta.cards
                    .map { it.idx }
                    .toSet()
                    .size == meta.cards.size,
            ) { "metadata contains duplicate indices" }
            require(
                meta.cards
                    .map { it.name }
                    .toSet()
                    .size == meta.cards.size,
            ) { "metadata contains duplicate names" }
            for (card in meta.cards) {
                require(card.idx in 0 until meta.nCards) { "metadata index ${card.idx} outside 0..${meta.nCards - 1}" }
            }
        }

        private fun validateLayers(weights: WeightsJson) {
            require(weights.pickNumberEmbeddings.isNotEmpty()) { "model has no pick number embeddings" }
            val embeddingSize = weights.pickNumberEmbeddings.first().size
            require(weights.pickNumberEmbeddings.all { it.size == embeddingSize }) {
                "pick number embeddings have inconsistent sizes"
            }
            var inputSize = weights.nCards * 2 + embeddingSize
            for (layer in weights.mlp) {
                inputSize = layer.validate(inputSize)
            }
            require(inputSize == weights.nCards) { "model output size $inputSize does not match n_cards ${weights.nCards}" }
        }
    }
}

private fun File.readMaybeGzipText(): String =
    if (name.endsWith(".gz")) {
        GZIPInputStream(inputStream()).bufferedReader().use { it.readText() }
    } else {
        readText()
    }

sealed interface ModelLayer {
    fun forward(input: DoubleArray): DoubleArray
}

class LinearLayer(
    private val weights: List<DoubleArray>,
    private val bias: DoubleArray,
) : ModelLayer {
    override fun forward(input: DoubleArray): DoubleArray {
        val out = DoubleArray(weights.size)
        for (i in weights.indices) {
            val row = weights[i]
            require(row.size == input.size) { "linear layer expected ${row.size} inputs, got ${input.size}" }
            var sum = bias[i]
            for (j in row.indices) sum += row[j] * input[j]
            out[i] = sum
        }
        return out
    }
}

class LayerNormLayer(
    private val weights: DoubleArray,
    private val bias: DoubleArray,
) : ModelLayer {
    override fun forward(input: DoubleArray): DoubleArray {
        require(weights.size == input.size && bias.size == input.size) { "layernorm size does not match input" }
        val mean = input.average()
        var variance = 0.0
        for (value in input) {
            val delta = value - mean
            variance += delta * delta
        }
        variance /= input.size
        val std = sqrt(variance + 1e-5)
        return DoubleArray(input.size) { idx -> weights[idx] * ((input[idx] - mean) / std) + bias[idx] }
    }
}

object ReluLayer : ModelLayer {
    override fun forward(input: DoubleArray): DoubleArray = DoubleArray(input.size) { idx -> input[idx].coerceAtLeast(0.0) }
}

@Serializable
private data class WeightsJson(
    val set: String,
    @SerialName("n_cards") val nCards: Int,
    @SerialName("picknum_emb") val pickNumberEmbeddings: List<List<Double>>,
    val mlp: List<LayerJson>,
)

@Serializable
private data class LayerJson(
    val type: String,
    @SerialName("W") val weights: List<List<Double>> = emptyList(),
    val b: List<Double> = emptyList(),
    val w: List<Double> = emptyList(),
) {
    fun validate(inputSize: Int): Int =
        when (type) {
            "linear" -> {
                require(weights.isNotEmpty()) { "linear layer has no weights" }
                require(b.size == weights.size) { "linear layer bias size ${b.size} does not match rows ${weights.size}" }
                for (row in weights) {
                    require(row.size == inputSize) { "linear layer expected $inputSize inputs, got ${row.size}" }
                }
                weights.size
            }

            "layernorm" -> {
                require(w.size == inputSize && b.size == inputSize) { "layernorm size does not match input" }
                inputSize
            }

            "relu" -> inputSize
            else -> error("unknown layer type $type")
        }

    fun toLayer(): ModelLayer =
        when (type) {
            "linear" -> LinearLayer(weights.map { it.toDoubleArray() }, b.toDoubleArray())
            "layernorm" -> LayerNormLayer(w.toDoubleArray(), b.toDoubleArray())
            "relu" -> ReluLayer
            else -> error("unknown layer type $type")
        }
}

@Serializable
private data class CardMetaJson(
    val set: String,
    @SerialName("n_cards") val nCards: Int,
    val cards: List<CardMetaEntryJson>,
)

@Serializable
private data class CardMetaEntryJson(
    val idx: Int,
    val name: String,
)
