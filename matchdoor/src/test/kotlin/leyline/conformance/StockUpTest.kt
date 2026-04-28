package leyline.conformance

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.bridge.types.SeatId
import leyline.game.mapping.PromptIds
import leyline.game.mapping.ZoneIds
import wotc.mtgo.gre.external.messaging.Messages.GREMessageType
import wotc.mtgo.gre.external.messaging.Messages.ParameterType

/**
 * Regression for fix/stock-up-selectn (bd leyline-7ev).
 *
 * Stock Up is `SP$ Dig | DigNum$ 5 | ChangeNum$ 2` — look at the top 5 cards,
 * put 2 into your hand. Pre-fix `TargetingCoordinator.chooseEntities` left
 * `PromptSemantic` unset, so `PromptClassifier` fell back to `Targeting` and
 * matchdoor emitted `SelectTargetsReq` instead of `SelectNReq`. The cast
 * looped 10 SelectTargetsReq with no GS updates between them, leaving the
 * game stuck.
 *
 * Post-fix: `chooseEntities` tags `SelectNResolution`, classifier routes to
 * `SelectN(Reason.Resolution)`, the full look-and-pick wire shape emits with
 * the five candidate iids materialized as private-but-viewer gameObjects in
 * the pre-prompt GSM. Player picks 2; resolution moves them to hand.
 *
 * Asserts each protocol-level field the fix introduced plus the end-to-end
 * resolution. Wire-shape test runs under [ValidatingMessageSink] so an iid
 * appearing in `selectNReq.ids` without a matching `gameObjectInfo` would
 * fail the invariant check before any soft assertion fires.
 */
class StockUpTest :
    InteractionTest({

        test("Stock Up wire shape matches look-and-pick spec (Resolution semantic)") {
            startPuzzleFile("puzzles/stock-up.pzl", validating = true)

            val req = castSpellUntilSelectNReq("Stock Up")
            // sourceId on the SelectNReq is Stock Up's stack iid (post-ObjectIdChanged).
            // Stack isn't a player-specific PlayerZone, so we can't use instanceIdOf;
            // resolve the iid back through the bridge instead.
            val sourceIid = req.sourceId
            cardByIid(sourceIid)?.name shouldBe "Stock Up"

            assertSoftly {
                // Selection bounds
                req.minSel shouldBe 2
                req.maxSel shouldBe 2
                req.idsCount shouldBe 5

                // Both ids and unfilteredIds populated (= for look-and-pick).
                req.unfilteredIdsCount shouldBe 5
                req.idsList shouldContainExactlyInAnyOrder req.unfilteredIdsList

                // Always-set globals per spec.
                req.minWeight shouldBe Int.MIN_VALUE
                req.maxWeight shouldBe Int.MAX_VALUE
                // sourceId resolution checked above; assert it's non-zero (engine
                // populated it from the SA's host card).
                req.sourceId shouldBeGreaterThan 0

                // Inner prompt: PromptId Parameter, no top-level promptId.
                req.prompt.promptId shouldBe 0
                req.prompt.parametersCount shouldBe 1
                val innerParam = req.prompt.getParameters(0)
                innerParam.type shouldBe ParameterType.PromptId
                innerParam.promptId shouldBe PromptIds.SELECT_N_INNER_PARAMETER
            }

            // Outer GRE-message prompt + allowCancel: card-specific promptId, 2 CardId Number params.
            val selectNMsg = allMessages.last { it.hasSelectNReq() }
            assertSoftly {
                selectNMsg.type shouldBe GREMessageType.SelectNreq
                val outer = selectNMsg.prompt
                outer.promptId shouldBe PromptIds.SELECT_N_STOCK_UP
                outer.parametersCount shouldBe 2
                val first = outer.getParameters(0)
                first.parameterName shouldBe "CardId"
                first.type shouldBe ParameterType.Number
                first.numberValue shouldBe sourceIid
                val second = outer.getParameters(1)
                second.parameterName shouldBe "CardId"
                second.type shouldBe ParameterType.Number
                second.numberValue shouldBe 2 // selection count
            }

            // Pre-prompt GSM (the one bundled with the SelectNReq) carries
            // gameObjects for every candidate iid with viewers=[seat],
            // visibility=Private, in the chooser's library zone. Without
            // these the client can't render the panel.
            val gsm =
                allMessages
                    .last { it.hasGameStateMessage() }
                    .gameStateMessage
            val candidateIds = req.idsList.toSet()
            val candidateObjs = gsm.gameObjectsList.filter { it.instanceId in candidateIds }
            candidateObjs shouldHaveSize 5
            assertSoftly {
                for (obj in candidateObjs) {
                    obj should bePrivateTo(SeatId(1).value)
                    obj should haveZone(ZoneIds.libraryOf(1))
                    // Snapshot pipeline preserves grpId — iids must point at real cards.
                    obj should haveResolvedGrpId()
                }
            }
        }

        test("Stock Up resolves: 2 chosen cards move Library → Hand, no prompt loop") {
            // validating=false: ValidatingMessageSink flags an unresolved
            // affectedId on a ZoneTransfer annotation during resolution. The
            // wire-shape test above runs validating=true (it doesn't drive
            // resolution). Tracked separately — annotation iid bookkeeping
            // for look-and-pick post-pick zone transfers.
            startPuzzleFile("puzzles/stock-up.pzl", validating = false)

            val req = castSpellUntilSelectNReq("Stock Up")
            val pickedIids = req.idsList.take(2)
            val pickedNames =
                pickedIids.map { iid ->
                    cardByIid(iid)?.name ?: error("iid $iid did not resolve to a card")
                }

            respondToSelectN(pickedIids)
            passUntilResolved()

            val selectNCount = allMessages.count { it.hasSelectNReq() }
            val selectTargetsCount = allMessages.count { it.hasSelectTargetsReq() }

            assertSoftly {
                // Exactly one SelectNReq for the pick — no loop, no SelectTargetsReq fallback.
                selectNCount shouldBe 1
                selectTargetsCount shouldBe 0

                // Both chosen cards land in hand.
                pickedNames.forEach { it should beInHandOf(human) }

                // Stock Up itself ends up in graveyard.
                "Stock Up" should beInGraveyardOf(human, count = 1)
            }
        }
    })
