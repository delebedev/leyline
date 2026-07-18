
@file:Suppress("ktlint")

package leyline.web

import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path

object WebOpenApi {
    private val json = Json { prettyPrint = true }

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

    @Suppress("LongMethod")
    private fun paths(): JsonObject =
        buildJsonObject {
            put("/gre", buildJsonObject { put("get", operation(responses = mapOf("101" to null))) })
            put("/api/auth/me", buildJsonObject { put("get", operation(response = ref("AuthView"))) })
            put("/api/auth/guest", buildJsonObject { put("post", operation(response = ref("AuthView"))) })
            put("/api/puzzles", buildJsonObject { put("get", operation(response = arrayRef("PuzzleSummaryView"))) })
            put(
                "/api/auth/request-code",
                buildJsonObject {
                    put(
                        "post",
                        operation(
                            request = ref("RequestLoginCodeRequest"),
                            responses =
                                mapOf(
                                    "204" to null,
                                ),
                        ),
                    )
                },
            )
            put(
                "/api/auth/verify",
                buildJsonObject {
                    put(
                        "post",
                        operation(
                            request = ref("VerifyLoginCodeRequest"),
                            response = ref("LoginResponse"),
                            responses =
                                mapOf(
                                    "401" to null,
                                ),
                        ),
                    )
                },
            )
            put("/api/auth/logout", buildJsonObject { put("post", operation(responses = mapOf("204" to null))) })
            put(
                "/api/gre/start",
                buildJsonObject {
                    put(
                        "post",
                        operation(
                            request = ref("GreStartRequest"),
                            response = ref("DraftPlayResponse"),
                            responses =
                                mapOf(
                                    "401" to null,
                                ),
                        ),
                    )
                },
            )
            put(
                "/api/public/gre/start",
                buildJsonObject {
                    put("post", operation(request = ref("GreStartRequest"), response = ref("DraftPlayResponse")))
                },
            )
            put(
                "/api/public/spectator/start",
                buildJsonObject {
                    put("post", operation(response = ref("PublicSpectatorResponse")))
                },
            )
            put(
                "/api/public/spectate/viewers",
                buildJsonObject {
                    put("get", operation(response = ref("ViewerCountView")))
                },
            )
            put("/api/collection", buildJsonObject { put("get", operation(response = ref("CollectionView"), responses = authFailures())) })
            put(
                "/api/public/cards/by-grpids",
                buildJsonObject {
                    put("get", operation(response = mapRef("GreCardMetaDto"), responses = mapOf("400" to null)))
                },
            )
            put(
                "/api/cards/metadata",
                buildJsonObject {
                    put("get", operation(response = ref("CardMetadataView")))
                },
            )
            put(
                "/api/cards/search",
                buildJsonObject {
                    put("get", operation(response = arrayRef("DraftCardDto"), responses = mapOf("400" to null)))
                },
            )
            put(
                "/api/cards/parse-decklist",
                buildJsonObject {
                    put(
                        "post",
                        operation(
                            request = ref("ParseDecklistRequest"),
                            response = ref("ParseDecklistResponse"),
                            responses = mapOf("400" to null),
                        ),
                    )
                },
            )
            put("/api/courses", buildJsonObject { put("get", operation(response = arrayRef("CourseView"), responses = authFailures())) })
            put(
                "/api/decks",
                buildJsonObject {
                    put("get", operation(response = arrayRef("DeckView"), responses = authFailures()))
                    put("post", operation(request = ref("CreateDeckRequest"), response = ref("DeckView"), responses = authFailures()))
                },
            )
            put(
                "/api/decks/{deckId}",
                buildJsonObject {
                    put("get", operation(response = ref("DeckView"), responses = mapOf("404" to null)))
                    put(
                        "delete",
                        operation(
                            responses =
                                mapOf(
                                    "204" to null,
                                ),
                        ),
                    )
                },
            )
            put(
                "/api/draft/start",
                buildJsonObject {
                    put(
                        "post",
                        operation(request = ref("StartDraftRequest"), response = ref("DraftSessionView"), responses = authFailures()),
                    )
                },
            )
            put(
                "/api/draft/pick",
                buildJsonObject {
                    put(
                        "post",
                        operation(request = ref("PickDraftRequest"), response = ref("DraftSessionView"), responses = authFailures()),
                    )
                },
            )
            put(
                "/api/draft/status",
                buildJsonObject {
                    put(
                        "get",
                        operation(
                            response = ref("DraftSessionView"),
                            responses =
                                authFailures() + mapOf("404" to null),
                        ),
                    )
                },
            )
            put(
                "/api/draft/deck",
                buildJsonObject {
                    put(
                        "post",
                        operation(
                            request = ref("SubmitDeckRequest"),
                            response = ref("CourseView"),
                            responses =
                                authFailures() + mapOf("400" to null),
                        ),
                    )
                },
            )
            put(
                "/api/draft/play",
                buildJsonObject {
                    put(
                        "post",
                        operation(request = ref("PlayDraftRequest"), response = ref("DraftPlayResponse"), responses = authFailures()),
                    )
                },
            )
            put("/api/draft", buildJsonObject { put("delete", operation(responses = authFailures() + mapOf("204" to null))) })
            put("/api/sealed/sets", buildJsonObject { put("get", operation(response = arrayRef("LimitedSetView"))) })
            put(
                "/api/sealed/start",
                buildJsonObject {
                    put("post", operation(request = ref("StartDraftRequest"), response = ref("CourseView"), responses = authFailures()))
                },
            )
            put(
                "/api/sealed/deck",
                buildJsonObject {
                    put(
                        "post",
                        operation(
                            request = ref("SubmitDeckRequest"),
                            response = ref("CourseView"),
                            responses = authFailures() + mapOf("400" to null),
                        ),
                    )
                },
            )
            put(
                "/api/sealed/play",
                buildJsonObject {
                    put(
                        "post",
                        operation(request = ref("PlayDraftRequest"), response = ref("DraftPlayResponse"), responses = authFailures()),
                    )
                },
            )
            put("/api/sealed", buildJsonObject { put("delete", operation(responses = authFailures() + mapOf("204" to null))) })
        }

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
            component("PuzzleSummaryView", serializer<PuzzleSummaryView>())
            component("RequestLoginCodeRequest", serializer<RequestLoginCodeRequest>())
            component("VerifyLoginCodeRequest", serializer<VerifyLoginCodeRequest>())
            component("LoginResponse", serializer<LoginResponse>())
            component("CardMetadataView", serializer<CardMetadataView>())
            component("CardMetadataEntry", serializer<CardMetadataEntry>())
            component("GreCardMetaDto", serializer<GreCardMetaDto>())
            component("DraftCardDto", serializer<DraftCardDto>())
            component("ParseDecklistRequest", serializer<ParseDecklistRequest>())
            component("ParsedCardDto", serializer<ParsedCardDto>())
            component("ParseDecklistResponse", serializer<ParseDecklistResponse>())
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
