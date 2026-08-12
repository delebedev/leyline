package leyline.game.state

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.SeatId
import leyline.game.event.GameEvent

class ProjectionAnnotationJournalTest :
    FunSpec({
        tags(UnitTag)

        test("discarded journal reduction leaves every lifecycle family unchanged") {
            val before = ProjectionAnnotationJournal()

            val discarded = ProjectionAnnotationJournal.Planner(before)
            discarded.recordAbility(AbilityWireIdentity(132, 118, 28, 147760))
            discarded.recordSpellCast(GameEvent.SpellCast(ForgeCardId(1), SeatId(1), spellGrpId = 95537), 95537)
            discarded.recordSpellResolution(GameEvent.SpellResolved(ForgeCardId(1), hasFizzled = false, spellGrpId = 95537), 95537)
            discarded.recordParadigmSourceStackIid(ForgeCardId(2), 134)
            discarded.recordDecayedCleanupSource(ForgeCardId(3))
            discarded.replaceActiveSteals(setOf(ForgeCardId(4)))

            assertSoftly {
                before shouldBe ProjectionAnnotationJournal()
                discarded
                    .transition()
                    .next.abilityLineage
                    .find(132)
                    ?.sourceIidAtCreate shouldBe 118
                discarded
                    .transition()
                    .next.pendingSpellCasts
                    .find(ForgeCardId(1), 95537)
                    ?.spellGrpId shouldBe 95537
                discarded
                    .transition()
                    .next.pendingSpellResolutions
                    .find(ForgeCardId(1), 95537)
                    ?.spellGrpId shouldBe 95537
                discarded.transition().next.paradigmSourceStackIids[ForgeCardId(2)] shouldBe 134
                discarded.transition().next.decayedCleanupSources shouldBe setOf(ForgeCardId(3))
                discarded.transition().next.activeStealForgeCardIds shouldBe setOf(ForgeCardId(4))
            }
        }

        test("equal reductions produce an equal next journal with same-frame lookup and consume") {
            fun reduce(): ProjectionAnnotationJournal.Transition {
                val planner = ProjectionAnnotationJournal.Planner(ProjectionAnnotationJournal())
                val cast = GameEvent.SpellCast(ForgeCardId(1), SeatId(1), spellGrpId = 95537)
                planner.recordSpellCast(cast, 95537)
                planner.pendingSpellCast(ForgeCardId(1), 95537) shouldBe cast
                planner.consumeSpellCast(ForgeCardId(1))
                planner.recordAbility(AbilityWireIdentity(132, 118, 28, 147760))
                planner.consumeAbility(132)?.abilityGrpId shouldBe 147760
                return planner.transition()
            }

            val first = reduce()
            val second = reduce()

            assertSoftly {
                first.next shouldBe second.next
                first.next.pendingSpellCasts
                    .find(ForgeCardId(1), 95537)
                    .shouldBeNull()
                first.expected shouldBe ProjectionAnnotationJournal()
            }
        }

        test("consuming an older spell retains a newer shared face fallback") {
            val older = "older"
            val newer = "newer"
            val faceGrpId = 95537
            val journal =
                PendingSpellEventRegistry<String>()
                    .record(ForgeCardId(1), faceGrpId, older)
                    .record(ForgeCardId(2), faceGrpId, newer)

            journal.consume(ForgeCardId(1)).find(ForgeCardId(3), faceGrpId) shouldBe newer
        }

        test("replacing an older spell retains a newer shared face fallback") {
            val firstFaceGrpId = 95537
            val secondFaceGrpId = 95538
            val journal =
                PendingSpellEventRegistry<String>()
                    .record(ForgeCardId(1), firstFaceGrpId, "older")
                    .record(ForgeCardId(2), firstFaceGrpId, "newer")
                    .record(ForgeCardId(1), secondFaceGrpId, "older-replaced")

            journal.find(ForgeCardId(3), firstFaceGrpId) shouldBe "newer"
        }

        test("all annotation journal lifecycles reduce from one tentative value") {
            val planner = ProjectionAnnotationJournal.Planner(ProjectionAnnotationJournal())
            val cardId = ForgeCardId(1)
            val cast = GameEvent.SpellCast(cardId, SeatId(1), spellGrpId = 95537)
            val resolution = GameEvent.SpellResolved(cardId, hasFizzled = false, spellGrpId = 95537)

            planner.recordAbility(AbilityWireIdentity(132, 118, 28, 147760))
            planner.consumeAbility(132)?.abilityGrpId shouldBe 147760
            planner.recordSpellCast(cast, 95537)
            planner.recordSpellResolution(resolution, 95537)
            planner.consumeSpellCast(cardId)
            planner.consumeSpellResolution(cardId)
            planner.recordParadigmSourceStackIid(cardId, 134)
            planner.recordParadigmSourceStackIidIfAbsent(cardId, 135)
            planner.recordDecayedCleanupSource(cardId)
            planner.clearDecayedCleanupSource(cardId)
            planner.replaceActiveSteals(setOf(cardId))
            planner.replaceActiveSteals(emptySet())

            val next = planner.transition().next
            assertSoftly {
                next.abilityLineage.find(132).shouldBeNull()
                next.pendingSpellCasts.find(cardId, 95537).shouldBeNull()
                next.pendingSpellResolutions.find(cardId, 95537).shouldBeNull()
                next.paradigmSourceStackIids[cardId] shouldBe 134
                next.decayedCleanupSources shouldBe emptySet()
                next.activeStealForgeCardIds shouldBe emptySet()
            }
        }
    })
