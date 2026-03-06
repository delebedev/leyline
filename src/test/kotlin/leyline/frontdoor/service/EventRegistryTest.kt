package leyline.frontdoor.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import leyline.FdTag

class EventRegistryTest :
    FunSpec({
        tags(FdTag)

        val json = Json { ignoreUnknownKeys = true }

        test("queue config JSON is a valid array with all queue entries") {
            val result = EventRegistry.toQueueConfigJson()
            val arr = json.parseToJsonElement(result).jsonArray
            arr shouldHaveAtLeastSize 14

            val std = arr.first { it.jsonObject["Id"]?.jsonPrimitive?.content == "StandardRanked" }.jsonObject
            std["EventNameBO1"]?.jsonPrimitive?.content shouldBe "Ladder"
            std["EventNameBO3"]?.jsonPrimitive?.content shouldBe "Traditional_Ladder"
            std["DeckSizeBO1"]?.jsonPrimitive?.content shouldBe "Events/Deck_60plus"
        }

        test("queue config includes AIBotMatch") {
            val result = EventRegistry.toQueueConfigJson()
            result shouldContain "AIBotMatch"
        }

        test("active events JSON has Events array with all events") {
            val result = EventRegistry.toActiveEventsJson()
            val obj = json.parseToJsonElement(result).jsonObject
            val events = obj["Events"]?.jsonArray ?: error("no Events")
            events shouldHaveAtLeastSize 13

            val ladder = events.first {
                it.jsonObject["InternalEventName"]?.jsonPrimitive?.content == "Ladder"
            }.jsonObject
            ladder["FormatType"]?.jsonPrimitive?.content shouldBe "Constructed"
            ladder["EventState"]?.jsonPrimitive?.content shouldBe "Active"
            val ux = ladder["EventUXInfo"]?.jsonObject ?: error("no EventUXInfo")
            ux["DeckSelectFormat"]?.jsonPrimitive?.content shouldBe "Standard"
            ux["Group"]?.jsonPrimitive?.content shouldBe ""
        }

        test("every event has non-null Group in EventUXInfo") {
            val result = EventRegistry.toActiveEventsJson()
            val events = json.parseToJsonElement(result).jsonObject["Events"]!!.jsonArray
            for (event in events) {
                val name = event.jsonObject["InternalEventName"]?.jsonPrimitive?.content
                val group = event.jsonObject["EventUXInfo"]?.jsonObject?.get("Group")
                check(group != null) { "Event $name has null Group — client will NRE" }
            }
        }

        test("every queue EventNameBO1/BO3 has a matching active event") {
            val eventNames = EventRegistry.events.map { it.internalName }.toSet()
            for (q in EventRegistry.queues) {
                check(q.eventNameBO1 in eventNames) {
                    "Queue ${q.id} references EventNameBO1='${q.eventNameBO1}' but no matching event exists — client will lock the tab"
                }
                if (q.eventNameBO3 != null) {
                    check(q.eventNameBO3 in eventNames) {
                        "Queue ${q.id} references EventNameBO3='${q.eventNameBO3}' but no matching event exists — client will lock the tab"
                    }
                }
            }
        }

        test("courses JSON has default entries referencing valid events") {
            val eventNames = EventRegistry.events.map { it.internalName }.toSet()
            val result = EventRegistry.toCoursesJson()
            val courses = json.parseToJsonElement(result).jsonObject["Courses"]!!.jsonArray
            courses shouldHaveAtLeastSize 1
            for (course in courses) {
                val name = course.jsonObject["InternalEventName"]!!.jsonPrimitive.content
                check(name in eventNames) { "Course references unknown event '$name'" }
            }
        }

        test("findEvent returns known event") {
            val event = EventRegistry.findEvent("Ladder")
            event shouldBe EventRegistry.events.first { it.internalName == "Ladder" }
        }

        test("findEvent returns null for unknown") {
            EventRegistry.findEvent("NonExistent") shouldBe null
        }

        test("forgeFormatFor returns mapped format for ranked event") {
            EventRegistry.forgeFormatFor("Ladder") shouldBe "Standard"
            EventRegistry.forgeFormatFor("Explorer_Ladder") shouldBe "Pioneer"
            EventRegistry.forgeFormatFor("Historic_Play") shouldBe "Historic"
        }

        test("forgeFormatFor returns null for AIBotMatch (SkipDeckValidation)") {
            EventRegistry.forgeFormatFor("AIBotMatch").shouldBeNull()
        }

        test("forgeFormatFor returns null for unknown event") {
            EventRegistry.forgeFormatFor("NonexistentEvent").shouldBeNull()
        }

        test("forgeFormatFor strips Traditional prefix") {
            EventRegistry.forgeFormatFor("Traditional_Ladder") shouldBe "Standard"
        }
    })
