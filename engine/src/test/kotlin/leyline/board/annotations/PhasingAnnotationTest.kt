package leyline.board.annotations

import forge.game.card.Card
import forge.game.card.CounterEnumType
import forge.game.event.GameEventCardCounters
import forge.game.event.GameEventTurnPhase
import forge.game.phase.PhaseType
import forge.game.trigger.WrappedAbility
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.bridge.types.ForgeCardId
import leyline.game.mapping.ActionMapper
import leyline.game.mapping.ZoneIds
import leyline.game.snapshot.SnapshotCapture
import leyline.testkit.BoardTest
import leyline.testkit.detailInt
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage

class PhasingAnnotationTest :
    BoardTest({
        fun GameStateMessage.shouldPhaseOutWithinResolution(
            affectedIid: Int,
            resolutionIid: Int,
        ) {
            val out = annotationsList.single { AnnotationType.PhasedOut_af5a in it.typeList }
            val start = annotationsList.single { AnnotationType.ResolutionStart in it.typeList }
            val complete = annotationsList.single { AnnotationType.ResolutionComplete in it.typeList }
            assertSoftly {
                out.affectedIdsList shouldBe listOf(affectedIid)
                out.affectorId shouldBe resolutionIid
                start.affectorId shouldBe resolutionIid
                complete.affectorId shouldBe resolutionIid
                out.detailsCount shouldBe 0
                annotationsList.indexOf(out).shouldBeGreaterThan(annotationsList.indexOf(start))
                annotationsList.indexOf(complete).shouldBeGreaterThan(annotationsList.indexOf(out))
            }
        }

        test("spell phase out uses the resolving spell instance inside its bracket") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Slip Out the Back", human, ZoneType.Hand)
                    addCard("Grizzly Bears", human, ZoneType.Battlefield)
                }
            val creature = board.human.battlefield.card("Grizzly Bears")
            val creatureIid = board.instanceId(creature.id)
            val spell = board.human.hand.card("Slip Out the Back")
            val ability =
                spell.firstSpellAbility.also {
                    it.activatingPlayer = board.human
                    it.targets.add(creature)
                }
            board.game.action.moveToStack(spell, ability)
            board.game.stack.addAndUnfreeze(ability)
            board.stateOnlyDiff()
            val spellIid = board.instanceId(spell.id)

            val resolved = board.snapshotDiff { board.game.stack.resolveStack() }

            creature.isPhasedOut shouldBe true
            resolved.shouldPhaseOutWithinResolution(creatureIid, spellIid)
        }

        test("trigger phase out uses the wrapped stack instance inside its bracket") {
            val board = startWithBoard { _, human, _ -> addCard("Kaito Shizuki", human, ZoneType.Battlefield) }
            val kaito = board.human.battlefield.card("Kaito Shizuki")
            val kaitoIid = board.instanceId(kaito.id)
            val trigger = kaito.triggers.single { it.overridingAbility != null }
            val wrapped = WrappedAbility(trigger, trigger.overridingAbility, board.human)
            board.game.stack.addAndUnfreeze(wrapped)
            val stacked = board.stateOnlyDiff()
            val triggerIid =
                stacked.zonesList
                    .single { it.zoneId == ZoneIds.STACK }
                    .objectInstanceIdsList
                    .single()
            triggerIid shouldNotBe kaitoIid
            wrapped.id shouldNotBe trigger.overridingAbility.id

            val resolved = board.snapshotDiff { board.game.stack.resolveStack() }

            kaito.isPhasedOut shouldBe true
            resolved.shouldPhaseOutWithinResolution(kaitoIid, triggerIid)
        }

        test("resumed natural untap stays before the following upkeep boundary") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield).setTapped(true)
                }
            val creature = board.human.battlefield.card("Grizzly Bears")
            board.snapshotDiff { board.game.phaseHandler.devModeSet(PhaseType.UNTAP, board.human) }
            val resumed =
                board.snapshotDiff {
                    creature.untap()
                    board.game.phaseHandler.devModeSet(PhaseType.UPKEEP, board.human)
                }
            resumed.annotationsList
                .filter {
                    AnnotationType.PhaseOrStepModified in it.typeList || AnnotationType.TappedUntappedPermanent in it.typeList
                }.map { it.typeList.single() } shouldBe
                listOf(
                    AnnotationType.TappedUntappedPermanent,
                    AnnotationType.PhaseOrStepModified,
                )
        }

        test("effect untap from Main retains the existing leading boundary policy") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield).setTapped(true)
                }
            val creature = board.human.battlefield.card("Grizzly Bears")
            board.snapshotDiff { board.game.phaseHandler.devModeSet(PhaseType.MAIN1, board.human) }
            val frame =
                board.snapshotDiff {
                    creature.untap()
                    board.game.phaseHandler.devModeSet(PhaseType.UPKEEP, board.human)
                }
            frame.annotationsList
                .filter {
                    AnnotationType.PhaseOrStepModified in it.typeList || AnnotationType.TappedUntappedPermanent in it.typeList
                }.map { it.typeList.single() } shouldBe
                listOf(
                    AnnotationType.PhaseOrStepModified,
                    AnnotationType.TappedUntappedPermanent,
                )
        }

        test("ordinary untap and a later effect untap of the same card each emit once") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Battlefield).setTapped(true)
                }
            val creature = board.human.battlefield.card("Grizzly Bears")
            val iid = board.instanceId(creature.id)
            val frame =
                board.snapshotDiff {
                    board.game.fireEvent(GameEventTurnPhase(board.human, PhaseType.UNTAP, ""))
                    creature.untap()
                    board.game.fireEvent(GameEventTurnPhase(board.human, PhaseType.UPKEEP, ""))
                    creature.setTapped(true)
                    creature.untap()
                }
            val rows =
                frame.annotationsList.filter {
                    AnnotationType.PhaseOrStepModified in it.typeList || AnnotationType.TappedUntappedPermanent in it.typeList
                }
            assertSoftly {
                rows.map { it.typeList.single() } shouldBe
                    listOf(
                        AnnotationType.PhaseOrStepModified,
                        AnnotationType.TappedUntappedPermanent,
                        AnnotationType.PhaseOrStepModified,
                        AnnotationType.TappedUntappedPermanent,
                    )
                rows.filter { AnnotationType.TappedUntappedPermanent in it.typeList }.map { it.affectedIdsList } shouldBe
                    listOf(listOf(iid), listOf(iid))
                frame.annotationsList.none { AnnotationType.PhasedIn in it.typeList } shouldBe true
            }
        }

        test("phasing preserves battlefield membership identity and counters without transfers") {
            lateinit var creature: Card
            val board =
                startWithBoard { _, human, _ ->
                    creature = addCard("Grizzly Bears", human, ZoneType.Battlefield)
                    creature.setCounters(CounterEnumType.P1P1, 2)
                }
            val iid = board.instanceId(creature.id)
            board.snapshotDiff { board.game.fireEvent(GameEventCardCounters(creature, CounterEnumType.P1P1, 0, 2)) }
            val baseline = handshakeFull(board.game, board.bridge, 20)
            baseline.zonesList.single { it.zoneId == ZoneIds.BATTLEFIELD }.objectInstanceIdsList shouldBe listOf(iid)
            val counterBefore =
                board.bridge.projectionStateSnapshot().persistentAnnotations.activeAnnotations.values.single {
                    AnnotationType.Counter_803b in it.typeList && iid in it.affectedIdsList
                }
            val out = board.snapshotDiff { creature.phase(false) }
            val phased = out.gameObjectsList.single { it.instanceId == iid }
            assertSoftly {
                phased.zoneId shouldBe ZoneIds.PHASED_OUT
                board.instanceId(creature.id) shouldBe iid
                creature.getCounters(CounterEnumType.P1P1) shouldBe 2
                out.zonesList.none { it.zoneId == ZoneIds.PHASED_OUT } shouldBe true
                out.zonesList.none { it.zoneId == ZoneIds.BATTLEFIELD } shouldBe true
                board.bridge
                    .projectionStateSnapshot()
                    .persistentAnnotations.activeAnnotations[counterBefore.id] shouldBe counterBefore
                counterBefore.detailInt("count") shouldBe 2
                out.annotationsList.single { AnnotationType.PhasedOut_af5a in it.typeList }.let {
                    it.affectedIdsList shouldBe listOf(iid)
                    it.affectorId shouldBe 0
                    it.detailsCount shouldBe 0
                }
                out.annotationsList.none {
                    AnnotationType.ObjectIdChanged in it.typeList || AnnotationType.ZoneTransfer_af5a in it.typeList
                } shouldBe
                    true
            }
            val snap = SnapshotCapture.run(board.game, board.bridge, "test", 0)
            snap.zones
                .getValue(ZoneIds.BATTLEFIELD)
                .contents shouldContain ForgeCardId(creature.id)
            val steady = board.stateOnlyDiff()
            steady.annotationsList.none { AnnotationType.PhasedOut_af5a in it.typeList || AnnotationType.PhasedIn in it.typeList } shouldBe
                true
            val inside = board.snapshotDiff { creature.phase(true) }
            assertSoftly {
                inside.gameObjectsList.single { it.instanceId == iid }.zoneId shouldBe ZoneIds.BATTLEFIELD
                board.instanceId(creature.id) shouldBe iid
                creature.getCounters(CounterEnumType.P1P1) shouldBe 2
                inside.annotationsList.single { AnnotationType.PhasedIn in it.typeList }.let {
                    it.affectedIdsList shouldBe listOf(iid)
                    it.affectorId shouldBe 0
                    it.detailsCount shouldBe 0
                }
                inside.zonesList.none { it.zoneId == ZoneIds.BATTLEFIELD } shouldBe true
                board.bridge
                    .projectionStateSnapshot()
                    .persistentAnnotations.activeAnnotations[counterBefore.id] shouldBe counterBefore
                inside.annotationsList.none {
                    AnnotationType.ObjectIdChanged in it.typeList || AnnotationType.ZoneTransfer_af5a in it.typeList
                } shouldBe
                    true
            }
            val exile = board.snapshotDiff { exile(creature, board.game) }
            exile.annotationsList.any { AnnotationType.ZoneTransfer_af5a in it.typeList } shouldBe true
            board.instanceId(creature.id) shouldNotBe iid
        }

        test("phased mana sources have no active or inactive actions while other sources remain") {
            lateinit var island: Card
            val board =
                startWithBoard { _, human, _ ->
                    island = addCard("Island", human, ZoneType.Battlefield)
                    addCard("Forest", human, ZoneType.Battlefield)
                }
            val islandIid = board.instanceId(island.id)
            val forestIid = board.human.battlefield.iid("Forest")
            board.snapshotDiff { island.phase(false) }
            val snapshot = SnapshotCapture.run(board.game, board.bridge, "test", 0)
            for (actions in listOf(board.actions(), ActionMapper.buildNaiveActionsFromSnapshot(1, snapshot, board.bridge))) {
                (actions.actionsList + actions.inactiveActionsList).none { it.instanceId == islandIid } shouldBe true
                (actions.actionsList + actions.inactiveActionsList).any { it.instanceId == forestIid } shouldBe true
            }
        }
    })
