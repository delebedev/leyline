package leyline.domain.service

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.domain.CourseModule
import leyline.domain.DraftStatus
import leyline.domain.PlayerId
import leyline.domain.repo.InMemoryCourseRepository
import leyline.domain.repo.InMemoryDraftSessionRepository

/**
 * Scripted [DraftService.Driver] that hands out pre-baked packs of arbitrary
 * size and produces a synthetic pod on [complete].
 */
private class ScriptedDraftDriver(
    packs: List<List<Int>>,
    private val pod: DraftService.PodOutcome =
        DraftService.PodOutcome(
            playerPool = packs.flatten(),
            botDecks = List(7) { listOf(99000 + it) },
        ),
) : DraftService.Driver {
    private val remainingByPack = packs.map { it.toMutableList() }.toMutableList()
    private var currentPackIdx = 0
    private var pickIdx = 0
    private var completedKey: String? = null

    override fun start(
        sessionKey: String,
        setCode: String,
    ): List<Int> = remainingByPack.getOrNull(currentPackIdx)?.toList() ?: emptyList()

    override fun pick(
        sessionKey: String,
        grpId: Int,
    ): DraftService.PickOutcome {
        require(currentPackIdx < remainingByPack.size) { "no pack to pick from" }
        require(remainingByPack[currentPackIdx].remove(grpId)) { "card $grpId not in current pack" }
        pickIdx++
        val completedPackPickIdx = pickIdx - 1

        if (remainingByPack[currentPackIdx].isEmpty()) {
            currentPackIdx++
            pickIdx = 0
        }

        val complete = currentPackIdx >= remainingByPack.size
        val nextPack = if (complete) emptyList() else remainingByPack[currentPackIdx].toList()
        return DraftService.PickOutcome(
            packNumber = if (complete) currentPackIdx - 1 else currentPackIdx,
            pickNumber = if (complete) completedPackPickIdx else pickIdx,
            nextPack = nextPack,
            complete = complete,
        )
    }

    override fun complete(sessionKey: String): DraftService.PodOutcome {
        completedKey = sessionKey
        return pod
    }

    fun completedKey(): String? = completedKey
}

class DraftServiceTest :
    FunSpec({
        val playerId = PlayerId("test-player")
        val eventName = "QuickDraft_FDN_20260223"

        fun scriptedPacks(): List<List<Int>> =
            (0 until 3).map { pack ->
                (1..13).map { card -> 90000 + pack * 100 + card }
            }

        fun createService(packs: List<List<Int>> = scriptedPacks()): Pair<DraftService, ScriptedDraftDriver> {
            val repo = InMemoryDraftSessionRepository()
            val courseService =
                CourseService(InMemoryCourseRepository()) { _ ->
                    GeneratedPool(emptyList(), emptyList(), 0)
                }
            val driver = ScriptedDraftDriver(packs)
            return DraftService(repo, driver, courseService) to driver
        }

        test("startDraft creates session with first pack of 13 cards") {
            val (service, _) = createService()
            val session = service.startDraft(playerId, eventName)

            assertSoftly {
                session.status shouldBe DraftStatus.PickNext
                session.packNumber shouldBe 0
                session.pickNumber shouldBe 0
                session.draftPack shouldHaveSize 13
                session.pickedCards shouldHaveSize 0
            }
        }

        test("startDraft returns existing session if already started") {
            val (service, _) = createService()
            val first = service.startDraft(playerId, eventName)
            val second = service.startDraft(playerId, eventName)
            first.id shouldBe second.id
        }

        test("pick removes card from pack and adds to pickedCards") {
            val (service, _) = createService()
            val session = service.startDraft(playerId, eventName)
            val cardToPick = session.draftPack.first()

            val after = service.pick(playerId, eventName, cardToPick)

            assertSoftly {
                after.pickNumber shouldBe 1
                after.packNumber shouldBe 0
                after.draftPack shouldHaveSize 12
                after.pickedCards shouldBe listOf(cardToPick)
                after.status shouldBe DraftStatus.PickNext
            }
        }

        test("picking all 13 cards in pack 0 advances to pack 1") {
            val (service, _) = createService()
            var session = service.startDraft(playerId, eventName)

            repeat(13) {
                val card = session.draftPack.first()
                session = service.pick(playerId, eventName, card)
            }

            assertSoftly {
                session.packNumber shouldBe 1
                session.pickNumber shouldBe 0
                session.draftPack shouldHaveSize 13
                session.pickedCards shouldHaveSize 13
            }
        }

        test("picking all 39 cards completes draft and persists pod") {
            val (service, driver) = createService()
            var session = service.startDraft(playerId, eventName)

            repeat(39) {
                val card = session.draftPack.first()
                session = service.pick(playerId, eventName, card)
            }

            assertSoftly {
                session.status shouldBe DraftStatus.Completed
                session.pickNumber shouldBe 12
                session.pickedCards shouldHaveSize 39
                session.draftPack shouldHaveSize 0
                driver.completedKey() shouldBe session.id.value
            }
        }

        test("completion saves bot decks via repo.savePodResults") {
            val repo = InMemoryDraftSessionRepository()
            val packs = scriptedPacks()
            val expectedPod =
                DraftService.PodOutcome(
                    playerPool = packs.flatten(),
                    botDecks = List(7) { seat -> List(40) { 80000 + seat * 100 + it } },
                )
            val driver = ScriptedDraftDriver(packs, expectedPod)
            val courseService =
                CourseService(InMemoryCourseRepository()) { _ ->
                    GeneratedPool(emptyList(), emptyList(), 0)
                }
            val service = DraftService(repo, driver, courseService)
            var session = service.startDraft(playerId, eventName)

            repeat(39) {
                val card = session.draftPack.first()
                session = service.pick(playerId, eventName, card)
            }

            val saved = repo.findPodResults(session.id)
            assertSoftly {
                saved shouldHaveSize 7
                saved.forEach { it shouldHaveSize 40 }
                saved[0] shouldBe expectedPod.botDecks[0]
            }
        }

        test("getStatus returns current session state") {
            val (service, _) = createService()
            service.startDraft(playerId, eventName)

            val status = service.getStatus(playerId, eventName)
            assertSoftly {
                status shouldNotBe null
                status!!.status shouldBe DraftStatus.PickNext
                status.draftPack shouldHaveSize 13
            }
        }

        test("getStatus returns null for non-existent session") {
            val (service, _) = createService()
            service.getStatus(playerId, eventName) shouldBe null
        }

        test("drop removes draft session") {
            val (service, _) = createService()
            service.startDraft(playerId, eventName)
            service.getStatus(playerId, eventName) shouldNotBe null

            val dropped = service.drop(playerId, eventName)
            dropped.module shouldBe CourseModule.Complete
            service.getStatus(playerId, eventName) shouldBe null
        }

        test("drop closes a joined course when no draft session exists") {
            val (service, _) = createService()
            service.joinDraft(playerId, eventName)
            service.drop(playerId, eventName).module shouldBe CourseModule.Complete
        }

        test("final pick transitions the course to deck selection") {
            val courseRepo = InMemoryCourseRepository()
            val courseService = CourseService(courseRepo) { _ -> GeneratedPool(emptyList(), emptyList(), 0) }
            val service =
                DraftService(
                    InMemoryDraftSessionRepository(),
                    ScriptedDraftDriver(listOf(listOf(1001)), DraftService.PodOutcome(listOf(1001), emptyList())),
                    courseService,
                )

            val session = service.startDraft(playerId, eventName)
            service.pick(playerId, eventName, session.draftPack.single())

            val course = courseRepo.findByPlayerAndEvent(playerId, eventName)
            assertSoftly {
                course?.module shouldBe CourseModule.DeckSelect
                course?.cardPool shouldBe listOf(1001)
                course?.cardPoolByCollation?.single()?.collationId shouldBe 100058
            }
        }

        test("starting after a completed course creates a fresh draft") {
            val courseRepo = InMemoryCourseRepository()
            val courseService = CourseService(courseRepo) { _ -> GeneratedPool(emptyList(), emptyList(), 0) }
            val service = DraftService(InMemoryDraftSessionRepository(), ScriptedDraftDriver(listOf(listOf(1001))), courseService)

            val first = service.startDraft(playerId, eventName)
            courseService.claimPrize(playerId, eventName)
            val restarted = service.startDraft(playerId, eventName)

            assertSoftly {
                restarted.id shouldNotBe first.id
                restarted.status shouldBe DraftStatus.PickNext
                courseRepo.findByPlayerAndEvent(playerId, eventName)?.module shouldBe CourseModule.BotDraft
            }
        }

        test("variable pack sizes complete correctly") {
            val packs =
                listOf(
                    (1..14).toList(),
                    (101..114).toList(),
                    (201..213).toList(),
                )
            val (service, _) = createService(packs)
            var session = service.startDraft(playerId, eventName)

            val totalCards = 14 + 14 + 13
            repeat(totalCards) {
                val card = session.draftPack.first()
                session = service.pick(playerId, eventName, card)
            }

            assertSoftly {
                session.status shouldBe DraftStatus.Completed
                session.pickNumber shouldBe 12
                session.pickedCards shouldHaveSize totalCards
                session.draftPack shouldHaveSize 0
            }
        }

        test("discardIncompleteSessions clears in-flight sessions") {
            val (service, _) = createService()
            service.startDraft(playerId, eventName)
            service.getStatus(playerId, eventName) shouldNotBe null

            service.discardIncompleteSessions()
            service.getStatus(playerId, eventName) shouldBe null
        }
    })
