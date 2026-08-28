
@file:Suppress("ktlint")

package leyline.web

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import leyline.domain.json.productionJson
import java.nio.file.Files
import java.nio.file.Path

object WebOpenApi {
    private val json = productionJson { prettyPrint = true }

    fun generate(): String = json.encodeToString(JsonElement.serializer(), document()) + "\n"

    private fun document(): JsonObject =
        buildJsonObject {
            put("openapi", "3.1.0")
            put(
                "info",
                buildJsonObject {
                    put("title", "Leyline Web Door")
                    put("version", "0.1.0")
                },
            )
            put("paths", paths())
            put("components", buildJsonObject { put("schemas", schemas()) })
        }

    private fun paths(): JsonObject =
        buildJsonObject {
            endpoints().groupBy(Endpoint::path).forEach { (path, endpoints) ->
                put(
                    path,
                    buildJsonObject {
                        endpoints.forEach { endpoint ->
                            put(endpoint.method, operation(endpoint.request, endpoint.response, endpoint.responses))
                        }
                    },
                )
            }
        }

    @Suppress("LongMethod")
    private fun endpoints(): List<Endpoint> =
        listOf(
            Endpoint("/gre", "get", responses = statuses("101")),
            Endpoint("/api/auth/me", "get", response = ref("AuthView")),
            Endpoint("/api/auth/guest", "post", response = ref("AuthView")),
            Endpoint("/api/challenges", "get", response = arrayRef("ChallengeSummary")),
            Endpoint("/api/auth/request-code", "post", request = ref("RequestLoginCodeRequest"), responses = statuses("204")),
            Endpoint(
                "/api/auth/verify",
                "post",
                request = ref("VerifyLoginCodeRequest"),
                response = ref("LoginResponse"),
                responses = statuses("401"),
            ),
            Endpoint("/api/auth/logout", "post", responses = statuses("204")),
            Endpoint(
                "/api/gre/start",
                "post",
                request = ref("GreStartRequest"),
                response = ref("DraftPlayResponse"),
                responses = statuses("401"),
            ),
            Endpoint("/api/public/spectator/start", "post", response = ref("PublicSpectatorResponse")),
            Endpoint("/api/public/spectate/viewers", "get", response = ref("ViewerCountView")),
            Endpoint("/api/collection", "get", response = ref("CollectionView"), responses = authFailures()),
            Endpoint("/api/public/cards/by-grpids", "get", response = mapRef("GreCardMetaDto"), responses = statuses("400")),
            Endpoint("/api/cards/metadata", "get", response = ref("CardMetadataView")),
            Endpoint("/api/cards/search", "get", response = arrayRef("DraftCardDto"), responses = statuses("400")),
            Endpoint(
                "/api/cards/parse-decklist",
                "post",
                request = ref("ParseDecklistRequest"),
                response = ref("ParseDecklistResponse"),
                responses = statuses("400"),
            ),
            Endpoint("/api/courses", "get", response = arrayRef("CourseView"), responses = authFailures()),
            Endpoint("/api/decks", "get", response = arrayRef("DeckView"), responses = authFailures()),
            Endpoint("/api/decks", "post", ref("CreateDeckRequest"), ref("DeckView"), authFailures()),
            Endpoint("/api/decks/{deckId}", "get", response = ref("DeckView"), responses = statuses("404")),
            Endpoint("/api/decks/{deckId}", "delete", responses = statuses("204")),
            Endpoint("/api/draft/start", "post", ref("StartDraftRequest"), ref("DraftSessionView"), authFailures()),
            Endpoint("/api/draft/pick", "post", ref("PickDraftRequest"), ref("DraftSessionView"), authFailures()),
            Endpoint("/api/draft/status", "get", response = ref("DraftSessionView"), responses = authFailures() + statuses("404")),
            Endpoint("/api/draft/deck", "post", ref("SubmitDeckRequest"), ref("CourseView"), authFailures() + statuses("400")),
            Endpoint("/api/draft/play", "post", ref("PlayDraftRequest"), ref("DraftPlayResponse"), authFailures()),
            Endpoint("/api/draft", "delete", responses = authFailures() + statuses("204")),
            Endpoint("/api/sealed/sets", "get", response = arrayRef("LimitedSetView")),
            Endpoint("/api/sealed/start", "post", ref("StartDraftRequest"), ref("CourseView"), authFailures()),
            Endpoint("/api/sealed/deck", "post", ref("SubmitDeckRequest"), ref("CourseView"), authFailures() + statuses("400")),
            Endpoint("/api/sealed/play", "post", ref("PlayDraftRequest"), ref("DraftPlayResponse"), authFailures()),
            Endpoint("/api/sealed", "delete", responses = authFailures() + statuses("204")),
        )

    private data class Endpoint(
        val path: String,
        val method: String,
        val request: JsonObject? = null,
        val response: JsonObject? = null,
        val responses: Map<String, JsonElement?> = emptyMap(),
    )

    private fun statuses(vararg values: String): Map<String, JsonElement?> = values.associateWith { null }

    private fun authFailures(): Map<String, JsonElement?> = mapOf("401" to null, "403" to null)

    private fun operation(
        request: JsonObject? = null,
        response: JsonObject? = null,
        responses: Map<String, JsonElement?> = emptyMap(),
    ): JsonObject =
        buildJsonObject {
            if (request != null) {
                put(
                    "requestBody",
                    buildJsonObject {
                        put("required", true)
                        put("content", jsonContent(request))
                    },
                )
            }
            put(
                "responses",
                buildJsonObject {
                    if (response != null) {
                        put(
                            "200",
                            buildJsonObject {
                                put("description", "OK")
                                put("content", jsonContent(response))
                            },
                        )
                    }
                    responses.forEach { (status, schema) ->
                        put(
                            status,
                            buildJsonObject {
                                put("description", responseDescription(status))
                                if (schema !=
                                    null
                                ) {
                                    put("content", jsonContent(schema))
                                }
                            },
                        )
                    }
                },
            )
        }

    private fun responseDescription(status: String): String =
        when (status) {
            "101" -> "WebSocket upgrade"
            "204" -> "No content"
            "400" -> "Bad request"
            "401" -> "Unauthorized"
            "403" -> "Forbidden"
            "404" -> "Not found"
            else -> "Response"
        }

    private fun jsonContent(schema: JsonElement): JsonObject =
        buildJsonObject { put("application/json", buildJsonObject { put("schema", schema) }) }

    private fun ref(name: String): JsonObject = buildJsonObject { put("\$ref", "#/components/schemas/$name") }

    private fun arrayRef(name: String): JsonObject =
        buildJsonObject {
            put("type", "array")
            put("items", ref(name))
        }

    private fun mapRef(name: String): JsonObject =
        buildJsonObject {
            put("type", "object")
            put("additionalProperties", ref(name))
        }

    private fun schemas(): JsonObject =
        buildJsonObject {
            component("StartDraftRequest", serializer<StartDraftRequest>())
            component("PickDraftRequest", serializer<PickDraftRequest>())
            component("SubmitDeckRequest", serializer<SubmitDeckRequest>())
            component("PlayDraftRequest", serializer<PlayDraftRequest>())
            component("GreStartRequest", serializer<GreStartRequest>())
            component("WebDeckCard", serializer<WebDeckCard>())
            component("DraftSessionView", serializer<DraftSessionView>())
            component("CourseView", serializer<CourseView>())
            component("DraftPlayResponse", serializer<DraftPlayResponse>())
            component("PublicSpectatorResponse", serializer<PublicSpectatorResponse>())
            component("PublicSeatView", serializer<PublicSeatView>())
            component("ViewerCountView", serializer<ViewerCountView>())
            component("CreateDeckRequest", serializer<CreateDeckRequest>())
            component("DeckView", serializer<DeckView>())
            component("CollectionView", serializer<CollectionView>())
            component("LimitedSetView", serializer<LimitedSetView>())
            component("LimitedSetArchetypeView", serializer<LimitedSetArchetypeView>())
            component("AuthView", serializer<AuthView>())
            component("ChallengeSummary", serializer<ChallengeSummary>())
            component("RequestLoginCodeRequest", serializer<RequestLoginCodeRequest>())
            component("VerifyLoginCodeRequest", serializer<VerifyLoginCodeRequest>())
            component("LoginResponse", serializer<LoginResponse>())
            component("CardMetadataView", serializer<CardMetadataView>())
            component("CardMetadataEntry", serializer<CardMetadataEntry>())
            component("GreCardMetaDto", serializer<GreCardMetaDto>())
            component("DraftCardDto", serializer<DraftCardDto>())
            component("ParseDecklistRequest", serializer<ParseDecklistRequest>())
            component("DecklistCardDto", serializer<DecklistCardDto>())
            component("ParseDecklistResponse", serializer<ParseDecklistResponse>())
            component("ParseDecklistErrorResponse", serializer<ParseDecklistErrorResponse>())
        }

    private fun <T> JsonObjectBuilder.component(
        name: String,
        serializer: KSerializer<T>,
    ) {
        put(name, schema(serializer.descriptor))
    }

    @Suppress("ElseCaseInsteadOfExhaustiveWhen")
    private fun schema(descriptor: SerialDescriptor): JsonObject =
        when (descriptor.kind) {
            PrimitiveKind.STRING -> buildJsonObject { put("type", "string") }
            PrimitiveKind.INT -> buildJsonObject { put("type", "integer") }
            PrimitiveKind.BOOLEAN -> buildJsonObject { put("type", "boolean") }
            StructureKind.LIST ->
                buildJsonObject {
                    put("type", "array")
                    put("items", schema(descriptor.getElementDescriptor(0)))
                }
            StructureKind.CLASS -> objectSchema(descriptor)
            StructureKind.MAP -> buildJsonObject { put("type", "object") }
            else -> buildJsonObject { put("type", "string") }
        }.nullableIf(descriptor.isNullable)

    private fun objectSchema(descriptor: SerialDescriptor): JsonObject =
        buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    for (i in 0 until descriptor.elementsCount) {
                        put(
                            descriptor.getElementName(i),
                            schema(descriptor.getElementDescriptor(i)),
                        )
                    }
                },
            )
            val required =
                buildJsonArray {
                    for (i in 0 until descriptor.elementsCount) {
                        if (!descriptor.isElementOptional(i) &&
                            !descriptor.getElementDescriptor(i).isNullable
                        ) {
                            add(JsonPrimitive(descriptor.getElementName(i)))
                        }
                    }
                }
            if (required.isNotEmpty()) put("required", required)
            put("additionalProperties", false)
        }

    private fun JsonObject.nullableIf(nullable: Boolean): JsonObject =
        if (!nullable) {
            this
        } else {
            JsonObject(this + ("nullable" to JsonPrimitive(true)))
        }
}

private typealias JsonObjectBuilder = kotlinx.serialization.json.JsonObjectBuilder

fun main(args: Array<String>) {
    val output = args.firstOrNull()
    if (output == null) {
        print(WebOpenApi.generate())
    } else {
        Files.writeString(Path.of(output), WebOpenApi.generate())
    }
}
