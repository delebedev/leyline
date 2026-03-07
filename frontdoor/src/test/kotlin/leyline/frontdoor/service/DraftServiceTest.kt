package leyline.frontdoor.service

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.frontdoor.domain.DraftStatus
import leyline.frontdoor.domain.PlayerId
import leyline.frontdoor.repo.InMemoryDraftSessionRepository

class DraftServiceTest :
    FunSpec({

        val playerId = PlayerId("test-player")
        val eventName = "QuickDraft_ECL_20260223"

        fun createService(): DraftService {
            val repo = InMemoryDraftSessionRepository()
            val generatePacks: (String) -> List<List<Int>> = { _ ->
                (0 until 3).map { pack ->
                    (1..13).map { card -> 90000 + pack * 100 + card }
                }
            }
            return DraftService(repo, generatePacks)
        }

        test("startDraft creates session with first pack of 13 cards") {
            val service = createService()
            val session = service.startDraft(playerId, eventName)

            session.status shouldBe DraftStatus.PickNext
            session.packNumber shouldBe 0
            session.pickNumber shouldBe 0
            session.draftPack shouldHaveSize 13
            session.pickedCards shouldHaveSize 0
            session.packs shouldHaveSize 3
        }

        test("startDraft returns existing session if already started") {
            val service = createService()
            val first = service.startDraft(playerId, eventName)
            val second = service.startDraft(playerId, eventName)
            first.id shouldBe second.id
        }

        test("pick removes card from pack and adds to pickedCards") {
            val service = createService()
            val session = service.startDraft(playerId, eventName)
            val cardToPick = session.draftPack.first()

            val after = service.pick(playerId, eventName, cardToPick, packNumber = 0, pickNumber = 0)

            after.pickNumber shouldBe 1
            after.packNumber shouldBe 0
            after.draftPack shouldHaveSize 12
            after.pickedCards shouldBe listOf(cardToPick)
            after.status shouldBe DraftStatus.PickNext
        }

        test("picking all 13 cards in pack 0 advances to pack 1") {
            val service = createService()
            var session = service.startDraft(playerId, eventName)

            for (i in 0 until 13) {
                val card = session.draftPack.first()
                session = service.pick(playerId, eventName, card, packNumber = session.packNumber, pickNumber = session.pickNumber)
            }

            session.packNumber shouldBe 1
            session.pickNumber shouldBe 0
            session.draftPack shouldHaveSize 13
            session.pickedCards shouldHaveSize 13
        }

        test("picking all 39 cards completes draft") {
            val service = createService()
            var session = service.startDraft(playerId, eventName)

            for (i in 0 until 39) {
                val card = session.draftPack.first()
                session = service.pick(playerId, eventName, card, packNumber = session.packNumber, pickNumber = session.pickNumber)
            }

            session.status shouldBe DraftStatus.Completed
            session.pickedCards shouldHaveSize 39
            session.draftPack shouldHaveSize 0
            session.packNumber shouldBe 2
            session.pickNumber shouldBe 13
        }

        test("getStatus returns current session state") {
            val service = createService()
            service.startDraft(playerId, eventName)

            val status = service.getStatus(playerId, eventName)
            status shouldNotBe null
            status!!.status shouldBe DraftStatus.PickNext
            status.draftPack shouldHaveSize 13
        }

        test("getStatus returns null for non-existent session") {
            val service = createService()
            service.getStatus(playerId, eventName) shouldBe null
        }
    })
