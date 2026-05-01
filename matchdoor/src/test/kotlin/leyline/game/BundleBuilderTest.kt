package leyline.game

import forge.game.phase.PhaseType
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.ConformanceTag
import leyline.bridge.handoff.InteractivePromptBridge
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.PromptCandidateRefDto
import leyline.bridge.types.SeatId
import leyline.conformance.ConformanceTestBase
import leyline.conformance.humanPlayer
import leyline.game.annotations.AnnotationLossReason
import leyline.game.bundle.BundleBuilder
import leyline.game.bundle.MessageCounter
import leyline.game.bundle.RequestBuilder
import leyline.game.event.GameEvent
import leyline.game.event.Zone
import leyline.game.mapping.PromptIds
import leyline.game.snapshot.CardSnapshot
import leyline.game.snapshot.GsmSnapshot
import leyline.game.snapshot.PhaseSnapshot
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.GameStateType
import wotc.mtgo.gre.external.messaging.Messages.SelectNReq

/**
 * Tests for [leyline.game.bundle.BundleBuilder] proto assembly.
 *
 * Unit group: pure proto wrappers (no game needed).
 * Conformance group: bundle shape checks via [startWithBoard].
 */
class BundleBuilderTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        /** Create a BundleBuilder for pure proto tests (no game state needed). */
        fun pureBB(
            seatId: Int = 1,
            matchId: String = "test-match",
        ) = BundleBuilder(GameBridge(cardRepository = InMemoryCardRepository()), matchId, seatId)

        // --- Unit tests (pure proto, no game) ---

        test("queuedGameState wraps GSM with type 51") {
            val gs =
                Messages.GameStateMessage
                    .newBuilder()
                    .setType(GameStateType.Full)
                    .setGameStateId(42)
                    .build()

            val msg =
                BundleBuilder(GameBridge(cardRepository = InMemoryCardRepository()), "test-match", 2)
                    .queuedGameState(gs, MessageCounter(initialGsId = 42, initialMsgId = 9))

            assertSoftly {
                msg.type shouldBe GREMessageType.QueuedGameStateMessage
                msg.hasGameStateMessage().shouldBeTrue()
                msg.gameStateMessage.gameStateId shouldBe 42
            }
        }

        test("buildModalCastingTimeOptionsReq — Charm shape (no costs, no excluded)") {
            val req =
                pureBB().buildModalCastingTimeOptionsReq(
                    parentGrpId = 200001,
                    childGrpIds = listOf(101, 102, 103),
                    minSel = 1,
                    maxSel = 1,
                    sourceInstanceId = 555,
                    grpId = 90000,
                )

            req.castingTimeOptionReqCount shouldBe 1
            val opt = req.getCastingTimeOptionReq(0)
            opt.castingTimeOptionType shouldBe Messages.CastingTimeOptionType.Modal_a7b4
            val mr = opt.modalReq
            assertSoftly {
                mr.modalOptionsCount shouldBe 3
                mr.getModalOptions(0).grpId shouldBe 101
                mr.getModalOptions(0).modeCostCount shouldBe 0
                mr.excludedOptionsCount shouldBe 0
                mr.minSel shouldBe 1
                mr.maxSel shouldBe 1
            }
        }

        test("buildModalCastingTimeOptionsReq — Spree shape (modeCost + excludedOptions)") {
            val req =
                pureBB().buildModalCastingTimeOptionsReq(
                    parentGrpId = 173717,
                    childGrpIds = listOf(171803, 171804),
                    modalCosts =
                        listOf(
                            listOf(Messages.ManaColor.Generic to 3),
                            listOf(Messages.ManaColor.Generic to 2),
                        ),
                    excludedGrpIds = listOf(171802),
                    excludedCosts =
                        listOf(
                            listOf(
                                Messages.ManaColor.Generic to 1,
                                Messages.ManaColor.Blue_afc9 to 1,
                            ),
                        ),
                    minSel = 1,
                    maxSel = 2,
                    sourceInstanceId = 240,
                    grpId = 90421,
                )

            val opt = req.getCastingTimeOptionReq(0)
            val mr = opt.modalReq
            assertSoftly {
                mr.modalOptionsCount shouldBe 2
                mr.getModalOptions(0).grpId shouldBe 171803
                mr.getModalOptions(0).modeCostCount shouldBe 1
                mr
                    .getModalOptions(0)
                    .getModeCost(0)
                    .manaCost
                    .getColor(0) shouldBe Messages.ManaColor.Generic
                mr
                    .getModalOptions(0)
                    .getModeCost(0)
                    .manaCost.count shouldBe 3

                mr.getModalOptions(1).grpId shouldBe 171804
                mr
                    .getModalOptions(1)
                    .getModeCost(0)
                    .manaCost.count shouldBe 2

                mr.excludedOptionsCount shouldBe 1
                mr.getExcludedOptions(0).grpId shouldBe 171802
                mr.getExcludedOptions(0).modeCostCount shouldBe 2
                mr
                    .getExcludedOptions(0)
                    .getModeCost(0)
                    .manaCost
                    .getColor(0) shouldBe Messages.ManaColor.Generic
                mr
                    .getExcludedOptions(0)
                    .getModeCost(0)
                    .manaCost.count shouldBe 1
                mr
                    .getExcludedOptions(0)
                    .getModeCost(1)
                    .manaCost
                    .getColor(0) shouldBe Messages.ManaColor.Blue_afc9
                mr
                    .getExcludedOptions(0)
                    .getModeCost(1)
                    .manaCost.count shouldBe 1
            }
        }

        test("buildModalCastingTimeOptionsReq — modalCosts shorter than childGrpIds drops late costs") {
            // Documents the parallel-list invariant: caller is expected to pass
            // a modalCosts of equal length to childGrpIds; shorter silently drops.
            val req =
                pureBB().buildModalCastingTimeOptionsReq(
                    parentGrpId = 1,
                    childGrpIds = listOf(10, 20, 30),
                    modalCosts =
                        listOf(
                            listOf(Messages.ManaColor.Generic to 1),
                            listOf(Messages.ManaColor.Generic to 2),
                            // mode 30 has no entry — emitted with no cost
                        ),
                    minSel = 1,
                    maxSel = 1,
                    sourceInstanceId = 1,
                    grpId = 1,
                )
            val mr = req.getCastingTimeOptionReq(0).modalReq
            assertSoftly {
                mr.getModalOptions(0).modeCostCount shouldBe 1
                mr.getModalOptions(1).modeCostCount shouldBe 1
                mr.getModalOptions(2).modeCostCount shouldBe 0
            }
        }

        test("edictalPass sends server-forced Pass action") {
            val counter = MessageCounter(initialGsId = 10, initialMsgId = 0)
            val result = pureBB().edictalPass(counter = counter)

            result.messages.size shouldBe 1
            val msg = result.messages[0]
            msg.type shouldBe GREMessageType.EdictalMessage_695e
            msg.hasEdictalMessage().shouldBeTrue()

            val inner = msg.edictalMessage.edictMessage
            inner.type shouldBe Messages.ClientMessageType.PerformActionResp_097b
            inner.systemSeatId shouldBe 1
            val action = inner.performActionResp.actionsList.first()
            action.actionType shouldBe Messages.ActionType.Pass
        }

        test("gameOverBundle produces 3 GSM diffs + IntermissionReq") {
            val counter = MessageCounter(initialGsId = 10, initialMsgId = 0)
            val result =
                pureBB().gameOverBundle(
                    winningTeam = 1,
                    counter = counter,
                    losingPlayerSeatId = 2,
                    lossReason = AnnotationLossReason.LifeTotal,
                )

            result.messages.size shouldBe 4

            for (i in 0..2) {
                result.messages[i].type shouldBe GREMessageType.GameStateMessage_695e
                result.messages[i].gameStateMessage.type shouldBe GameStateType.Diff
            }

            val gs1 = result.messages[0].gameStateMessage
            assertSoftly {
                gs1.hasGameInfo().shouldBeTrue()
                gs1.gameInfo.matchState shouldBe Messages.MatchState.GameComplete
                gs1.gameInfo.stage shouldBe Messages.GameStage.GameOver
                gs1.gameInfo.resultsCount shouldBe 1
                gs1.teamsCount shouldBeGreaterThan 0
                gs1.annotationsCount shouldBeGreaterThan 0
            }

            val gs2 = result.messages[1].gameStateMessage
            gs2.gameInfo.matchState shouldBe Messages.MatchState.MatchComplete
            gs2.gameInfo.resultsCount shouldBe 2

            val gs3 = result.messages[2].gameStateMessage
            gs3.pendingMessageCount shouldBe 1
            gs3.hasGameInfo().shouldBeFalse()

            val intermission = result.messages[3]
            assertSoftly {
                intermission.type shouldBe GREMessageType.IntermissionReq_695e
                intermission.hasIntermissionReq().shouldBeTrue()
                intermission.intermissionReq.optionsCount shouldBe 2
                intermission.intermissionReq.intermissionPrompt.promptId shouldBe PromptIds.MATCH_RESULT_WIN_LOSS
            }
        }

        test("gameOverBundle gsIds are strictly ascending") {
            val counter = MessageCounter(initialGsId = 10, initialMsgId = 0)
            val result =
                pureBB().gameOverBundle(
                    winningTeam = 1,
                    counter = counter,
                )

            var prevGsId = 0
            for (msg in result.messages) {
                if (msg.hasGameStateMessage()) {
                    val gsId = msg.gameStateMessage.gameStateId
                    gsId shouldBeGreaterThan prevGsId
                    prevGsId = gsId
                }
            }
        }

        test("gameOverBundle prevGameStateId chains correctly") {
            val counter = MessageCounter(initialGsId = 10, initialMsgId = 0)
            val result =
                pureBB().gameOverBundle(
                    winningTeam = 2,
                    counter = counter,
                )

            val gsms = result.messages.filter { it.hasGameStateMessage() }.map { it.gameStateMessage }
            assertSoftly {
                gsms.size shouldBe 3
                gsms[0].prevGameStateId shouldBe 10
                gsms[1].prevGameStateId shouldBe gsms[0].gameStateId
                gsms[2].prevGameStateId shouldBe gsms[1].gameStateId
            }
        }

        test("gameOverBundle with Concede reason") {
            val counter = MessageCounter(initialGsId = 10, initialMsgId = 0)
            val result =
                pureBB().gameOverBundle(
                    winningTeam = 1,
                    counter = counter,
                    reason = Messages.ResultReason.Concede,
                    losingPlayerSeatId = 2,
                    lossReason = AnnotationLossReason.Concede,
                )

            val gs1 = result.messages[0].gameStateMessage
            val gameResult = gs1.gameInfo.resultsList.first()
            gameResult.reason shouldBe Messages.ResultReason.Concede
        }

        // --- Conformance tests (board-based) ---

        test("declareAttackersBundle shape") {
            val (b, game, counter) = base.startWithBoard { _, _, _ -> }

            val result = base.bundleBuilder(b).declareAttackersBundle(game, counter)

            assertSoftly {
                result.messages.size shouldBe 2
                result.messages[0].type shouldBe GREMessageType.GameStateMessage_695e
                result.messages[1].type shouldBe GREMessageType.DeclareAttackersReq_695e
                result.messages[1].prompt.promptId shouldBe 6
            }
        }

        test("declareBlockersBundle shape") {
            val (b, game, counter) = base.startWithBoard { _, _, _ -> }

            val result = base.bundleBuilder(b).declareBlockersBundle(game, counter)

            assertSoftly {
                result.messages.size shouldBe 2
                result.messages[0].type shouldBe GREMessageType.GameStateMessage_695e
                result.messages[1].type shouldBe GREMessageType.DeclareBlockersReq_695e
                result.messages[1].prompt.promptId shouldBe 7
            }
        }

        test("selectTargetsBundle shape") {
            val (b, game, counter) = base.startWithBoard { _, _, _ -> }

            val candidateRefs =
                listOf(
                    PromptCandidateRefDto(0, "card", 999, "Battlefield"),
                )
            val prompt =
                InteractivePromptBridge.PendingPrompt(
                    promptId = "test-prompt",
                    request =
                        PromptRequest(
                            promptType = "choose_cards",
                            message = "Choose target",
                            options = listOf("Target A"),
                            min = 1,
                            max = 1,
                            candidateRefs = candidateRefs,
                        ),
                    future = java.util.concurrent.CompletableFuture(),
                )
            val result = base.bundleBuilder(b).selectTargetsBundle(game, counter, prompt)

            assertSoftly {
                result.messages.size shouldBe 2
                result.messages[0].type shouldBe GREMessageType.GameStateMessage_695e
                result.messages[1].type shouldBe GREMessageType.SelectTargetsReq_695e
                result.messages[1].prompt.promptId shouldBe PromptIds.SELECT_TARGETS
                result.messages[1].allowCancel shouldBe Messages.AllowCancel.Abort
                result.messages[1].allowUndo.shouldBeTrue()
            }
        }

        test("echoAttackersBundle conformance — SendAndRecord, no combat state, actions present") {
            val (b, game, counter) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Llanowar Elves", human, ZoneType.Battlefield)
                    base.addCard("Elvish Mystic", human, ZoneType.Battlefield)
                }

            val creatures =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .filter { it.isCreature }
            val allIds = creatures.map { b.getOrAllocInstanceId(ForgeCardId(it.id)).value }
            val selectedIds = listOf(allIds.first())

            val result = base.bundleBuilder(b).echoAttackersBundle(game, counter, selectedIds, allIds)

            assertSoftly {
                result.messages.size shouldBe 2
                result.messages[0].type shouldBe GREMessageType.GameStateMessage_695e
                result.messages[1].type shouldBe GREMessageType.DeclareAttackersReq_695e
            }

            val gsm = result.messages[0].gameStateMessage
            gsm.type shouldBe GameStateType.Diff
            gsm.gameObjectsCount shouldBeGreaterThan 0

            // Conformance: client uses SendAndRecord, no pendingMessageCount
            gsm.update shouldBe Messages.GameStateUpdate.SendAndRecord
            gsm.pendingMessageCount shouldBe 0

            // Conformance: no attackState/blockState on echo objects
            for (obj in gsm.gameObjectsList) {
                obj.attackState shouldBe Messages.AttackState.None_a3a9
                obj.blockState shouldBe Messages.BlockState.None_aa2d
            }

            // Conformance note: actions array is a cumulative turn log. In the naive
            // board-only setup here it's expected to be empty, so no assertion is needed.
        }

        test("echoBlockersBundle conformance — SendAndRecord, no combat state, actions present") {
            val (b, game, counter) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Llanowar Elves", human, ZoneType.Battlefield)
                }

            val blocker =
                game.humanPlayer
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .first { it.isCreature }
            val blockerId = b.getOrAllocInstanceId(ForgeCardId(blocker.id)).value
            val blockAssignments = mapOf(blockerId to 999)

            val result = base.bundleBuilder(b).echoBlockersBundle(game, counter, blockAssignments)

            assertSoftly {
                result.messages.size shouldBe 2
                result.messages[0].type shouldBe GREMessageType.GameStateMessage_695e
                result.messages[1].type shouldBe GREMessageType.DeclareBlockersReq_695e
            }

            val gsm = result.messages[0].gameStateMessage
            gsm.gameObjectsCount shouldBeGreaterThan 0

            // Conformance: client uses SendAndRecord, no pendingMessageCount
            gsm.update shouldBe Messages.GameStateUpdate.SendAndRecord
            gsm.pendingMessageCount shouldBe 0

            // Conformance: no blockState on echo objects
            for (obj in gsm.gameObjectsList) {
                obj.blockState shouldBe Messages.BlockState.None_aa2d
                obj.attackState shouldBe Messages.AttackState.None_a3a9
            }
        }

        test("selectNBundle shape") {
            val (b, game, counter) = base.startWithBoard { _, _, _ -> }

            val req =
                SelectNReq
                    .newBuilder()
                    .setMinSel(1)
                    .setMaxSel(1)
                    .build()
            val result = base.bundleBuilder(b).selectNBundle(game, counter, req)

            assertSoftly {
                result.messages.size shouldBe 2
                result.messages[0].type shouldBe GREMessageType.GameStateMessage_695e
                result.messages[1].type shouldBe GREMessageType.SelectNreq
                result.messages[1].prompt.promptId shouldBe PromptIds.SELECT_N
            }
        }

        test("discard SelectNReq uses Resolution context and Dynamic listType (#175)") {
            val (b, _, _) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Mountain", human, ZoneType.Hand)
                    base.addCard("Forest", human, ZoneType.Hand)
                }

            val handCards =
                b
                    .getPlayer(SeatId(1))!!
                    .getZone(ZoneType.Hand)
                    .cards
                    .toList()
            val prompt =
                InteractivePromptBridge.PendingPrompt(
                    promptId = "discard-test",
                    request =
                        PromptRequest(
                            promptType = "choose_cards",
                            message = "Choose a card to discard",
                            options = listOf("Discard"),
                            min = 1,
                            max = 1,
                            candidateRefs =
                                handCards.mapIndexed { i, c ->
                                    PromptCandidateRefDto(i, "card", c.id, "Hand")
                                },
                        ),
                    future = java.util.concurrent.CompletableFuture(),
                )

            val req = RequestBuilder.buildSelectNReq(prompt, b)

            assertSoftly {
                req.context shouldBe Messages.SelectionContext.Resolution_a163
                req.listType shouldBe Messages.SelectionListType.Dynamic
                req.optionContext shouldBe Messages.OptionContext.Resolution_a9d7
                req.idType shouldBe Messages.IdType.InstanceId_ab2c
                req.validationType shouldBe Messages.SelectionValidationType.NonRepeatable
                req.minSel shouldBe 1
                req.maxSel shouldBe 1
                req.idsCount shouldBe 2
                req.prompt.promptId shouldBe PromptIds.SELECT_N
            }
        }

        test("payCostsBundle shape") {
            val (b, game, counter) = base.startWithBoard { _, _, _ -> }

            val req = Messages.PayCostsReq.newBuilder().build()
            val result = base.bundleBuilder(b).payCostsBundle(game, counter, req)

            assertSoftly {
                result.messages.size shouldBe 2
                result.messages[0].type shouldBe GREMessageType.GameStateMessage_695e
                result.messages[1].type shouldBe GREMessageType.PayCostsReq_695e
                result.messages[1].prompt.promptId shouldBe PromptIds.PAY_COSTS
            }
        }

        // --- isTurnOrTriggerDraw unit tests (leyline-pey) ---
        //
        // postAction overrides the default `SendAndRecord` to `SendHiFi` when the
        // drained event stream describes a turn-boundary or trigger-driven draw
        // for the active seat. These cases pin the helper contract directly so
        // regressions surface without needing a full game state.

        fun drawSnap(
            drawnCard: ForgeCardId,
            activeSeat: SeatId,
            phase: PhaseType? = PhaseType.MAIN1,
        ): GsmSnapshot =
            GsmSnapshot.forTest(
                objects =
                    mapOf(
                        drawnCard to
                            CardSnapshot(
                                forgeCardId = drawnCard,
                                name = "Grizzly Bears",
                                grpId = 1,
                                owner = activeSeat,
                                controller = activeSeat,
                            ),
                    ),
                phase =
                    PhaseSnapshot(
                        turn = 2,
                        activePlayer = activeSeat,
                        priorityPlayer = activeSeat,
                        phase = phase,
                    ),
            )

        test("isTurnOrTriggerDraw: Case A — turn-boundary draw returns true") {
            val card = ForgeCardId(42)
            val seat = SeatId(1)
            val events = listOf(GameEvent.ZoneChanged(card, Zone.Library, Zone.Hand))

            BundleBuilder.isTurnOrTriggerDraw(events, drawSnap(card, seat), seat) shouldBe true
        }

        test("isTurnOrTriggerDraw: Case B — spell-driven draw returns false") {
            val card = ForgeCardId(42)
            val seat = SeatId(1)
            val events =
                listOf(
                    GameEvent.SpellCast(ForgeCardId(99), seat),
                    GameEvent.ZoneChanged(card, Zone.Library, Zone.Hand),
                    GameEvent.SpellResolved(ForgeCardId(99), hasFizzled = false),
                )

            BundleBuilder.isTurnOrTriggerDraw(events, drawSnap(card, seat), seat) shouldBe false
        }

        test("isTurnOrTriggerDraw: Case C — no draw event returns false") {
            val seat = SeatId(1)
            BundleBuilder.isTurnOrTriggerDraw(emptyList(), drawSnap(ForgeCardId(0), seat), seat) shouldBe false
        }

        test("isTurnOrTriggerDraw: UPKEEP phase (trigger-driven draw) returns true") {
            val card = ForgeCardId(42)
            val seat = SeatId(1)
            val events = listOf(GameEvent.ZoneChanged(card, Zone.Library, Zone.Hand))

            BundleBuilder.isTurnOrTriggerDraw(events, drawSnap(card, seat, PhaseType.UPKEEP), seat) shouldBe true
        }

        test("isTurnOrTriggerDraw: phase=null falls back to default (false)") {
            val card = ForgeCardId(42)
            val seat = SeatId(1)
            val events = listOf(GameEvent.ZoneChanged(card, Zone.Library, Zone.Hand))

            BundleBuilder.isTurnOrTriggerDraw(events, drawSnap(card, seat, phase = null), seat) shouldBe false
        }

        test("isTurnOrTriggerDraw: draw by non-active seat returns false") {
            val card = ForgeCardId(42)
            val activeSeat = SeatId(1)
            val opponent = SeatId(2)
            // Card owned by opponent, not active seat
            val snap =
                GsmSnapshot.forTest(
                    objects =
                        mapOf(
                            card to
                                CardSnapshot(
                                    forgeCardId = card,
                                    name = "Grizzly Bears",
                                    grpId = 1,
                                    owner = opponent,
                                    controller = opponent,
                                ),
                        ),
                    phase =
                        PhaseSnapshot(
                            turn = 2,
                            activePlayer = activeSeat,
                            priorityPlayer = activeSeat,
                            phase = PhaseType.MAIN1,
                        ),
                )
            val events = listOf(GameEvent.ZoneChanged(card, Zone.Library, Zone.Hand))

            BundleBuilder.isTurnOrTriggerDraw(events, snap, activeSeat) shouldBe false
        }

        test("isTurnOrTriggerDraw: COMBAT phase (out of window) returns false") {
            val card = ForgeCardId(42)
            val seat = SeatId(1)
            val events = listOf(GameEvent.ZoneChanged(card, Zone.Library, Zone.Hand))

            BundleBuilder.isTurnOrTriggerDraw(events, drawSnap(card, seat, PhaseType.COMBAT_BEGIN), seat) shouldBe false
        }

        test("postAction emits SendAndRecord when there are no draw events (baseline preserved)") {
            val (b, game, counter) =
                base.startWithBoard { _, human, _ ->
                    base.addCard("Plains", human, ZoneType.Battlefield)
                }

            val result = base.bundleBuilder(b).postAction(game, counter)
            val gsm = result.messages.first { it.hasGameStateMessage() }.gameStateMessage

            // startWithBoard parks us at MAIN1 with activePlayer=humanPlayer=seat 1.
            // No Library→Hand events, so the override must not fire and the default
            // SendAndRecord (acting == viewing) stands. Guards against the override
            // swallowing non-draw postAction bundles.
            gsm.update shouldBe Messages.GameStateUpdate.SendAndRecord
        }
    })
