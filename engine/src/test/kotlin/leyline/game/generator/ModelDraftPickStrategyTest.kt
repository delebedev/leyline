package leyline.game.generator

import forge.card.CardRarity
import forge.card.CardRules
import forge.card.ColorSet
import forge.gamemodes.limited.DraftPickContext
import forge.item.PaperCard
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPOutputStream

class ModelDraftPickStrategyTest :
    FunSpec({
        tags(UnitTag)

        test("model strategy picks highest-scored pack card") {
            val alpha = card("Alpha")
            val beta = card("Beta")
            val model =
                PickModelBundle(
                    nCards = 2,
                    pickNumberEmbeddings = listOf(doubleArrayOf(0.0)),
                    layers =
                        listOf(
                            LinearLayer(
                                weights = listOf(DoubleArray(5), DoubleArray(5)),
                                bias = doubleArrayOf(0.0, 2.0),
                            ),
                        ),
                    nameToIdx = mapOf("Alpha" to 0, "Beta" to 1),
                )

            val pick =
                ModelDraftPickStrategy(model).choose(
                    DraftPickContext(
                        1,
                        0,
                        listOf(alpha, beta),
                        emptyList(),
                        ColorSet.fromMask(0),
                        true,
                    ),
                )

            pick shouldBe beta
        }

        test("model bundle loads exported json shape") {
            val dir = Files.createTempDirectory("draft-model").toFile()
            val weights = File(dir, "weights.json")
            val meta = File(dir, "card_meta.json")
            weights.writeText(
                """
                {
                  "set": "tst",
                  "n_cards": 2,
                  "picknum_emb": [[0.0]],
                  "mlp": [
                    {"type": "linear", "W": [[0,0,0,0,0], [0,0,0,0,0]], "Wrows": 2, "Wcols": 5, "b": [0.0, 3.0]}
                  ]
                }
                """.trimIndent(),
            )
            meta.writeText(
                """
                {"set": "tst", "n_cards": 2, "cards": [{"idx": 0, "name": "Alpha"}, {"idx": 1, "name": "Beta"}]}
                """.trimIndent(),
            )

            val bundle = PickModelBundle.load(weights, meta)
            val logits = bundle.forward(doubleArrayOf(1.0, 1.0), doubleArrayOf(0.0, 0.0), 0)

            logits[1] shouldBe 3.0
        }

        test("model bundle loads gzipped weights") {
            val dir = Files.createTempDirectory("draft-model-gzip").toFile()
            val weights = File(dir, "weights.json.gz")
            val meta = File(dir, "card_meta.json")
            val json =
                """
                {
                  "set": "tst",
                  "n_cards": 2,
                  "picknum_emb": [[0.0]],
                  "mlp": [
                    {"type": "linear", "W": [[0,0,0,0,0], [0,0,0,0,0]], "Wrows": 2, "Wcols": 5, "b": [0.0, 4.0]}
                  ]
                }
                """.trimIndent()
            GZIPOutputStream(weights.outputStream()).bufferedWriter().use { it.write(json) }
            meta.writeText(
                """
                {"set": "tst", "n_cards": 2, "cards": [{"idx": 0, "name": "Alpha"}, {"idx": 1, "name": "Beta"}]}
                """.trimIndent(),
            )

            val logits = PickModelBundle.load(weights, meta).forward(doubleArrayOf(1.0, 1.0), doubleArrayOf(0.0, 0.0), 0)

            logits[1] shouldBe 4.0
        }

        test("model bundle rejects invalid metadata indices") {
            val dir = Files.createTempDirectory("draft-model-invalid-meta").toFile()
            val weights = File(dir, "weights.json")
            val meta = File(dir, "card_meta.json")
            weights.writeText(
                """
                {
                  "set": "tst",
                  "n_cards": 2,
                  "picknum_emb": [[0.0]],
                  "mlp": [
                    {"type": "linear", "W": [[0,0,0,0,0], [0,0,0,0,0]], "Wrows": 2, "Wcols": 5, "b": [0.0, 1.0]}
                  ]
                }
                """.trimIndent(),
            )
            meta.writeText(
                """
                {"set": "tst", "n_cards": 2, "cards": [{"idx": 0, "name": "Alpha"}, {"idx": 0, "name": "Beta"}]}
                """.trimIndent(),
            )

            shouldThrow<IllegalArgumentException> {
                PickModelBundle.load(weights, meta)
            }
        }
    })

private fun card(name: String): PaperCard = PaperCard(CardRules.getUnsupportedCardNamed(name), "TST", CardRarity.Common)
