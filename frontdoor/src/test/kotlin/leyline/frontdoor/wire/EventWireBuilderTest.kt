package leyline.frontdoor.wire

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import leyline.frontdoor.FdTag
import leyline.frontdoor.domain.*
import leyline.frontdoor.service.EventRegistry

class EventWireBuilderTest :
    FunSpec({
        tags(FdTag)

        val json = Json { ignoreUnknownKeys = true }

        fun sealedCourse(
            wins: Int = 0,
            losses: Int = 0,
            module: CourseModule = CourseModule.CreateMatch,
            deck: CourseDeck? = null,
            deckSummary: CourseDeckSummary? = null,
        ) = Course(
            id = CourseId("test-course-id"),
            playerId = PlayerId("test-player"),
            eventName = "Sealed_FDN_20260307",
            module = module,
            wins = wins,
            losses = losses,
            cardPool = (1..84).toList(),
            cardPoolByCollation = listOf(CollationPool(100026, (1..84).toList())),
            deck = deck,
            deckSummary = deckSummary,
        )

        fun testDeck() = CourseDeck(
            deckId = DeckId("deck1"),
            mainDeck = (1..40).map { DeckCard(it, 1) },
            sideboard = (41..84).map { DeckCard(it, 1) },
        )

        fun testDeckSummary() = CourseDeckSummary(
            deckId = DeckId("deck1"),
            name = "Sealed Deck",
            tileId = 12345,
            format = "Limited",
        )

        test("buildCourseJson omits CurrentWins when zero") {
            val obj = EventWireBuilder.buildCourseJson(sealedCourse(wins = 0))
            obj["CurrentWins"].shouldBeNull()
        }

        test("buildCourseJson includes CurrentWins when non-zero") {
            val obj = EventWireBuilder.buildCourseJson(sealedCourse(wins = 2))
            obj["CurrentWins"]?.jsonPrimitive?.int shouldBe 2
        }

        test("buildCourseJson omits CurrentLosses when zero") {
            val obj = EventWireBuilder.buildCourseJson(sealedCourse(losses = 0))
            obj["CurrentLosses"].shouldBeNull()
        }

        test("buildCourseJson includes CurrentLosses when non-zero") {
            val obj = EventWireBuilder.buildCourseJson(sealedCourse(losses = 1))
            obj["CurrentLosses"]?.jsonPrimitive?.int shouldBe 1
        }

        test("buildCourseJson has LossDetailsDisplay with Games for sealed") {
            val sealedDef = EventRegistry.findEvent("Sealed_FDN_20260307")!!
            val eventsJson = EventWireBuilder.toActiveEventsJson(listOf(sealedDef))
            val obj = json.parseToJsonElement(eventsJson).jsonObject
            val event = obj["Events"]!!.jsonArray[0].jsonObject
            val ldd = event["EventUXInfo"]!!.jsonObject["EventComponentData"]!!
                .jsonObject["LossDetailsDisplay"]!!.jsonObject
            ldd["Games"]?.jsonPrimitive?.int shouldBe 3
        }

        test("buildCourseJson has SelectedDeckWidget for sealed with deck") {
            val sealedDef = EventRegistry.findEvent("Sealed_FDN_20260307")!!
            val eventsJson = EventWireBuilder.toActiveEventsJson(listOf(sealedDef))
            val obj = json.parseToJsonElement(eventsJson).jsonObject
            val event = obj["Events"]!!.jsonArray[0].jsonObject
            val widget = event["EventUXInfo"]!!.jsonObject["EventComponentData"]!!
                .jsonObject["SelectedDeckWidget"]
            widget.shouldNotBeNull()
            widget.jsonObject["DeckButtonBehavior"]?.jsonPrimitive?.content shouldBe "Editable"
        }

        test("buildCourseJson omits SelectedDeckWidget when not sealed") {
            val ladderDef = EventRegistry.findEvent("Ladder")!!
            val eventsJson = EventWireBuilder.toActiveEventsJson(listOf(ladderDef))
            val obj = json.parseToJsonElement(eventsJson).jsonObject
            val event = obj["Events"]!!.jsonArray[0].jsonObject
            val widget = event["EventUXInfo"]!!.jsonObject["EventComponentData"]!!
                .jsonObject["SelectedDeckWidget"]
            widget.shouldBeNull()
        }

        test("buildJoinResponse has CourseDeck with CardPool") {
            val course = sealedCourse(module = CourseModule.DeckSelect)
            val responseJson = EventWireBuilder.buildJoinResponse(course)
            val obj = json.parseToJsonElement(responseJson).jsonObject
            val cardPool = obj["Course"]!!.jsonObject["CardPool"]!!.jsonArray
            cardPool.size shouldBe 84
        }

        test("buildMatchResultReport has CurrentModule") {
            val course = sealedCourse(losses = 1)
            val reportJson = EventWireBuilder.buildMatchResultReport(course)
            val obj = json.parseToJsonElement(reportJson).jsonObject
            obj["CurrentModule"].shouldNotBeNull()
            obj["CurrentModule"]!!.jsonPrimitive.content shouldBe "CreateMatch"
        }
    })
