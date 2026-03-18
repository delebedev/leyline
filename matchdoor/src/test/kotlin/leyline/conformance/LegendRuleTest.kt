package leyline.conformance

import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.ConformanceTag
import leyline.bridge.ForgeCardId
import leyline.bridge.WebPlayerController
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameStateMessage

/**
 * Legend rule SBA conformance: when two legendary permanents with the same
 * name are on the battlefield, the older one is put into the graveyard.
 *
 * Real server auto-resolves (no interactive prompt), keeps the newest,
 * and produces:
 * - ObjectIdChanged annotation (old instanceId → graveyard instanceId)
 * - ZoneTransfer annotation with category "SBA_LegendRule"
 *
 * Uses startWithBoard{} + WPC with auto-resolve — synchronous (~0.01s per test).
 * checkStateEffects(true) triggers SBAs inline on the test thread.
 *
 * Recording baseline: 2026-03-17_20-18-39 gsId=682 (Syr Alin legend rule)
 */
class LegendRuleTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        test("legend rule SBA produces SBA_LegendRule transfer category") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Isamaru, Hound of Konda", human, ZoneType.Battlefield)
                base.addCard("Plains", human, ZoneType.Battlefield)
            }
            val human = game.humanPlayer

            // Install WPC with auto-resolve for legend rule
            val prompt = b.promptBridge(1)
            val wpc = WebPlayerController(
                game = game,
                player = human,
                lobbyPlayer = human.lobbyPlayer,
                bridge = prompt,
            )

            // Second Isamaru — enters with summoning sickness (like just being cast)
            val second = base.addCard("Isamaru, Hound of Konda", human, ZoneType.Battlefield)
            second.setSickness(true)
            b.getOrAllocInstanceId(ForgeCardId(second.id))

            // Capture the first Isamaru's forge ID before SBA
            val first = human.getZone(ZoneType.Battlefield).cards
                .filter { it.name == "Isamaru, Hound of Konda" }
                .first { it !== second }
            val firstForgeId = first.id
            val origId = b.getOrAllocInstanceId(ForgeCardId(firstForgeId)).value

            // Run SBAs under the WPC so legend rule auto-resolves
            var gsm: GameStateMessage? = null
            human.runWithController({
                gsm = base.captureAfterAction(b, game, counter, checkSba = true) {}
            }, wpc)

            val result = checkNotNull(gsm) { "captureAfterAction should produce a GSM" }
            val newId = b.getOrAllocInstanceId(ForgeCardId(firstForgeId)).value

            // The old Isamaru should have a ZoneTransfer with SBA_LegendRule category
            val zt = checkNotNull(result.findZoneTransfer(newId) ?: result.findZoneTransfer(origId)) {
                "Expected ZoneTransfer annotation for legend rule victim"
            }
            zt.category shouldBe "SBA_LegendRule"

            // The old one should be in graveyard
            human.getZone(ZoneType.Graveyard).cards.any { it.id == firstForgeId } shouldBe true

            // The new one should remain on battlefield
            human.getZone(ZoneType.Battlefield).cards.any { it.id == second.id } shouldBe true
        }

        test("legend rule keeps newest legendary") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Isamaru, Hound of Konda", human, ZoneType.Battlefield)
                base.addCard("Plains", human, ZoneType.Battlefield)
            }
            val human = game.humanPlayer

            val prompt = b.promptBridge(1)
            val wpc = WebPlayerController(
                game = game,
                player = human,
                lobbyPlayer = human.lobbyPlayer,
                bridge = prompt,
            )

            val second = base.addCard("Isamaru, Hound of Konda", human, ZoneType.Battlefield)
            second.setSickness(true)
            b.getOrAllocInstanceId(ForgeCardId(second.id))

            human.runWithController({
                base.captureAfterAction(b, game, counter, checkSba = true) {}
            }, wpc)

            // Battlefield should have exactly one Isamaru — the new one
            val remaining = human.getZone(ZoneType.Battlefield).cards
                .filter { it.name == "Isamaru, Hound of Konda" }
            remaining.size shouldBe 1
            remaining.first().id shouldBe second.id
        }

        test("legend rule produces ObjectIdChanged annotation") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Isamaru, Hound of Konda", human, ZoneType.Battlefield)
                base.addCard("Plains", human, ZoneType.Battlefield)
            }
            val human = game.humanPlayer

            val prompt = b.promptBridge(1)
            val wpc = WebPlayerController(
                game = game,
                player = human,
                lobbyPlayer = human.lobbyPlayer,
                bridge = prompt,
            )

            val second = base.addCard("Isamaru, Hound of Konda", human, ZoneType.Battlefield)
            second.setSickness(true)
            b.getOrAllocInstanceId(ForgeCardId(second.id))

            val first = human.getZone(ZoneType.Battlefield).cards
                .filter { it.name == "Isamaru, Hound of Konda" }
                .first { it !== second }
            val origId = b.getOrAllocInstanceId(ForgeCardId(first.id)).value

            var gsm: GameStateMessage? = null
            human.runWithController({
                gsm = base.captureAfterAction(b, game, counter, checkSba = true) {}
            }, wpc)

            val result = checkNotNull(gsm)
            val newId = b.getOrAllocInstanceId(ForgeCardId(first.id)).value

            // ObjectIdChanged should be present when instanceId changes (zone transition realloc)
            if (origId != newId) {
                val oidAnn = result.annotationAffecting(AnnotationType.ObjectIdChanged, origId)
                oidAnn shouldNotBe null
            }
        }
    })
