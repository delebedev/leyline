package leyline.game.annotations

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.InMemoryCardRepository
import leyline.game.event.GameEvent
import leyline.game.mapping.ZoneIds
import leyline.game.state.GameBridge
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

class PhasingAnnotationPipelineTest :
    FunSpec({
        tags(UnitTag)
        val cardId = ForgeCardId(42)

        fun annotations(
            events: List<GameEvent>,
            transfers: List<AppliedTransfer> = emptyList(),
        ) = GameBridge(cardRepository = InMemoryCardRepository()).let { bridge ->
            val transfer = TransferResult(transfers, emptyList(), emptyList(), emptyList(), emptyList())
            AnnotationPipeline
                .computeAnnotations(
                    annotationContext(bridge, events = events, transferResult = transfer),
                    transfer,
                    1,
                ).annotations
        }

        test("phase out uses resolving trigger identity within its resolution bracket") {
            val rows =
                annotations(
                    listOf(
                        GameEvent.CardPhased(cardId, true, affectorAbilityForgeId = 91),
                        GameEvent.SpellResolved(cardId, false, isTrigger = true, abilityForgeId = 91, abilityGrpId = 100),
                    ),
                )
            val out = rows.single { AnnotationType.PhasedOut_af5a in it.typeList }
            val start = rows.single { AnnotationType.ResolutionStart in it.typeList }
            val complete = rows.single { AnnotationType.ResolutionComplete in it.typeList }
            assertSoftly {
                out.affectorId shouldBe start.affectorId
                out.affectorId.shouldBeGreaterThan(0)
                out.detailsCount shouldBe 0
                out.affectedIdsCount shouldBe 1
                out.affectedIdsList.single() shouldNotBe out.affectorId
                val deleted = rows.single { AnnotationType.AbilityInstanceDeleted in it.typeList }
                rows.indexOf(deleted).shouldBeGreaterThan(rows.indexOf(complete))
                rows.indexOf(out).shouldBeGreaterThan(rows.indexOf(start))
                rows.indexOf(complete).shouldBeGreaterThan(rows.indexOf(out))
            }
        }

        test("phase out without an active resolution has no affector from a later trigger") {
            val rows =
                annotations(
                    listOf(
                        GameEvent.CardPhased(cardId, true),
                        GameEvent.SpellResolved(ForgeCardId(43), false, isTrigger = true, abilityForgeId = 92, abilityGrpId = 100),
                    ),
                    transfers =
                        listOf(
                            AppliedTransfer(
                                800,
                                801,
                                TransferCategory.Resolve,
                                ZoneIds.STACK,
                                ZoneIds.P1_GRAVEYARD,
                                grpId = 100,
                                ownerSeatId = 1,
                            ),
                        ),
                )
            rows.single { AnnotationType.PhasedOut_af5a in it.typeList }.affectorId shouldBe 0
        }

        test("natural phase in follows the untap phase change") {
            val rows =
                annotations(
                    listOf(
                        GameEvent.PhaseChanged(SeatId(1), phase = 1, step = 1),
                        GameEvent.CardPhased(cardId, false),
                    ),
                )
            val inside = rows.single { AnnotationType.PhasedIn in it.typeList }
            val phase = rows.single { AnnotationType.PhaseOrStepModified in it.typeList }
            rows.indexOf(inside).shouldBeGreaterThan(rows.indexOf(phase))
        }

        test("natural phase in remains between untap and the following upkeep") {
            val rows =
                annotations(
                    listOf(
                        GameEvent.PhaseChanged(SeatId(1), phase = 1, step = 1),
                        GameEvent.CardPhased(cardId, false),
                        GameEvent.PhaseChanged(SeatId(1), phase = 1, step = 2),
                    ),
                )
            rows.map { it.typeList.single() } shouldBe
                listOf(
                    AnnotationType.PhaseOrStepModified,
                    AnnotationType.PhasedIn,
                    AnnotationType.PhaseOrStepModified,
                )
        }

        test("ordinary untap changes precede upkeep without collapsing repeated transitions") {
            val rows =
                annotations(
                    listOf(
                        GameEvent.PhaseChanged(SeatId(1), phase = 1, step = 1),
                        GameEvent.CardTapped(cardId, false),
                        GameEvent.PhaseChanged(SeatId(1), phase = 1, step = 2),
                        GameEvent.PhaseChanged(SeatId(1), phase = 1, step = 2),
                    ),
                )
            rows.map { it.typeList.single() } shouldBe
                listOf(
                    AnnotationType.PhaseOrStepModified,
                    AnnotationType.TappedUntappedPermanent,
                    AnnotationType.PhaseOrStepModified,
                    AnnotationType.PhaseOrStepModified,
                )
        }

        test("phase in has no affector even when another ability resolves in the frame") {
            val rows =
                annotations(
                    listOf(
                        GameEvent.CardPhased(cardId, false),
                        GameEvent.SpellResolved(ForgeCardId(43), false, isTrigger = true, abilityForgeId = 92, abilityGrpId = 100),
                    ),
                )
            val inside = rows.single { AnnotationType.PhasedIn in it.typeList }
            assertSoftly {
                inside.affectorId shouldBe 0
                inside.detailsCount shouldBe 0
                rows.count { AnnotationType.PhasedOut_af5a in it.typeList } shouldBe 0
            }
        }
    })
