package leyline.native.frontdoor.service

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import leyline.domain.service.EventRegistry
import leyline.native.NativeTag
import leyline.native.frontdoor.wire.EventWireBuilder

class EventRegistryTest :
    FunSpec({
        tags(NativeTag)

        val json = Json { ignoreUnknownKeys = true }

        test("queue config JSON is a valid array with all queue entries") {
            val result = EventWireBuilder.toQueueConfigJson(EventRegistry.queues)
            val arr = json.parseToJsonElement(result).jsonArray
            arr shouldHaveAtLeastSize EventRegistry.queues.size

            val bot = arr.first { it.jsonObject["Id"]?.jsonPrimitive?.content == "AIBotMatch" }.jsonObject
            bot["EventNameBO1"]?.jsonPrimitive?.content shouldBe "AIBotMatch"
        }

        test("queue config includes AIBotMatch") {
            val result = EventWireBuilder.toQueueConfigJson(EventRegistry.queues)
            result shouldContain "AIBotMatch"
        }

        test("active events JSON has all formats and AiBotMatches") {
            val result = EventWireBuilder.toActiveEventsJson(EventRegistry.activeEvents, EventRegistry.aiBotMatches)
            val obj = json.parseToJsonElement(result).jsonObject
            val events = obj["Events"]?.jsonArray ?: error("no Events")
            events shouldHaveAtLeastSize EventRegistry.activeEvents.size

            // AIBotMatch NOT in Events (lives in AiBotMatches)
            val names = events.map { it.jsonObject["InternalEventName"]?.jsonPrimitive?.content }
            names shouldNotContain "AIBotMatch"

            val bots = obj["AiBotMatches"]?.jsonArray ?: error("no AiBotMatches")
            bots shouldHaveAtLeastSize 2
        }

        test("active events include configured quick draft sets") {
            val result = EventWireBuilder.toActiveEventsJson(EventRegistry.activeEvents, EventRegistry.aiBotMatches)
            val events = json.parseToJsonElement(result).jsonObject["Events"]!!.jsonArray

            fun event(name: String) = events.first { it.jsonObject["InternalEventName"]?.jsonPrimitive?.content == name }.jsonObject

            fun publicEventName(name: String) = event(name)["EventUXInfo"]!!.jsonObject["PublicEventName"]!!.jsonPrimitive.content

            fun titleLocKey(name: String) =
                event(name)
                    .jsonObject["EventUXInfo"]!!
                    .jsonObject["EventComponentData"]!!
                    .jsonObject["TitleRankText"]!!
                    .jsonObject["LocKey"]!!
                    .jsonPrimitive.content

            publicEventName("QuickDraft_DMU_20260101") shouldBe "DMU_Quick_Draft"
            publicEventName("QuickDraft_MOM_20260101") shouldBe "MOM_Quick_Draft"
            publicEventName("QuickDraft_NEO_20260101") shouldBe "NEO_Quick_Draft"
            titleLocKey("QuickDraft_DMU_20260101") shouldBe "Events/Event_Title_DMU_Quick_Draft"
            titleLocKey("QuickDraft_MOM_20260101") shouldBe "Events/Event_Title_MOM_Quick_Draft"
            titleLocKey("QuickDraft_NEO_20260101") shouldBe "Events/Event_Title_NEO_Quick_Draft"
            EventRegistry.findEvent("QuickDraft_DMU_20260101")?.collationId shouldBe 100030
            EventRegistry.findEvent("QuickDraft_MOM_20260101")?.collationId shouldBe 100037
            EventRegistry.findEvent("QuickDraft_NEO_20260101")?.collationId shouldBe 100027
        }

        test("every event has non-null Group in EventUXInfo") {
            val result = EventWireBuilder.toActiveEventsJson(EventRegistry.events)
            val events = json.parseToJsonElement(result).jsonObject["Events"]!!.jsonArray
            val groupless =
                events
                    .filter { it.jsonObject["EventUXInfo"]?.jsonObject?.get("Group") == null }
                    .mapNotNull { it.jsonObject["InternalEventName"]?.jsonPrimitive?.content }

            withClue("events with a null EventUXInfo.Group — the client NREs on these") {
                groupless.shouldBeEmpty()
            }
        }

        test("every queue EventNameBO1/BO3 has a matching active event") {
            val eventNames = EventRegistry.events.map { it.internalName }.toSet()
            val dangling =
                EventRegistry.queues.flatMap { q ->
                    listOfNotNull(
                        "${q.id}.EventNameBO1=${q.eventNameBO1}".takeIf { q.eventNameBO1 !in eventNames },
                        "${q.id}.EventNameBO3=${q.eventNameBO3}".takeIf {
                            q.eventNameBO3 != null && q.eventNameBO3 !in eventNames
                        },
                    )
                }

            withClue("queue references with no matching event — the client locks the tab") {
                dangling.shouldBeEmpty()
            }
        }

        test("courses JSON has default entries referencing valid events") {
            val eventNames = EventRegistry.events.map { it.internalName }.toSet()
            val result = EventWireBuilder.toDefaultCoursesJson(EventRegistry.defaultCourses)
            val courses = json.parseToJsonElement(result).jsonObject["Courses"]!!.jsonArray
            courses shouldHaveAtLeastSize 1
            val unknown =
                courses
                    .map { it.jsonObject["InternalEventName"]!!.jsonPrimitive.content }
                    .filterNot(eventNames::contains)

            withClue("default courses referencing an unknown event") { unknown.shouldBeEmpty() }
        }

        test("findEvent returns known event") {
            val event = EventRegistry.findEvent("AIBotMatch")
            event shouldBe EventRegistry.events.first { it.internalName == "AIBotMatch" }
        }

        test("findEvent returns null for unknown") {
            EventRegistry.findEvent("NonExistent") shouldBe null
        }

        test("forgeFormatFor returns mapped format for known event") {
            EventRegistry.forgeFormatFor("Play_Brawl") shouldBe "Brawl"
        }

        test("forgeFormatFor returns null for AIBotMatch (SkipDeckValidation)") {
            EventRegistry.forgeFormatFor("AIBotMatch").shouldBeNull()
        }

        test("forgeFormatFor returns null for unknown event") {
            EventRegistry.forgeFormatFor("NonexistentEvent").shouldBeNull()
        }

        test("mapArenaFormat strips Traditional prefix") {
            EventRegistry.mapArenaFormat("TraditionalStandard") shouldBe "Standard"
        }
    })
