package leyline.domain.json

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

@OptIn(ExperimentalSerializationApi::class)
class ProductionJsonTest :
    FunSpec({
        test("decoding errors omit source input even when callers request debug details") {
            val sensitiveInput = "do-not-log-this"

            val error =
                shouldThrow<SerializationException> {
                    productionJson { exceptionsWithDebugInfo = true }
                        .decodeFromString<Payload>("""{"count":"$sensitiveInput"}""")
                }

            error.message.orEmpty() shouldNotContain sensitiveInput
        }
    })

@Serializable
private data class Payload(
    val count: Int,
)
