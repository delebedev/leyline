package leyline.conformance

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import wotc.mtgo.gre.external.messaging.Messages.AllowCancel
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.AutoPassOption
import wotc.mtgo.gre.external.messaging.Messages.GameStage
import wotc.mtgo.gre.external.messaging.Messages.HighlightType
import wotc.mtgo.gre.external.messaging.Messages.SelectAction
import wotc.mtgo.gre.external.messaging.Messages.SettingsMessage
import forge.game.zone.ZoneType as ForgeZoneType
import wotc.mtgo.gre.external.messaging.Messages.ZoneType as ProtoZoneType

/**
 * Session-tier targeting tests — SelectTargetsReq/Resp flow through MatchSession.
 *
 * Absorbs TargetingFlowTest, TwoPhaseTargetingTest, BiteDownTest. Organized by
 * mechanic: single-target creature (Giant Growth), player-targeted burn (Lightning
 * Bolt), two-phase protocol, and multi-group fight (Bite Down).
 *
 * The #92 auto-resolve regression test is parked here pending an AutoPass
 * consolidation file — see the TODO near the test.
 */
class TargetingInteractionTest :
    InteractionTest({

        // ─── Giant Growth: single-target creature buff ─────────────────────────

        test("Giant Growth — prompt shape, select, resolve, zone transfer") {
            startPuzzleFile("puzzles/pump-spell.pzl")

            val creatureIid = humanBattlefieldCreatures().first().first

            // Phase 1: prompt shape
            val snap = messageSnapshot()
            castSpellByName("Giant Growth").shouldBeTrue()

            val stReq = messagesSince(snap).firstOrNull { it.hasSelectTargetsReq() }
            stReq.shouldNotBeNull()
            val targetSelection = stReq.selectTargetsReq.targetsList.first()
            assertSoftly {
                targetSelection.minTargets shouldBe 1
                targetSelection.maxTargets shouldBe 1
                targetSelection.targetsList.map { it.targetInstanceId } shouldContain creatureIid
            }

            // Phase 2: select + resolve effects
            selectTargets(listOf(creatureIid))
            // Buff may take multiple passes to land (layered effect after resolve).
            passUntil(maxPasses = 6) { (cardByIid(creatureIid)?.netPower ?: 0) >= 4 }

            assertSoftly {
                // Creature buffed +3/+3 (Grizzly Bears 2/2 → 5/5)
                val creature = cardByIid(creatureIid)
                creature.shouldNotBeNull()
                creature.netPower shouldBeGreaterThanOrEqual 4
                creature.netToughness shouldBeGreaterThanOrEqual 4

                // Spell moved Stack → GY
                human.getZone(ForgeZoneType.Graveyard).cards
                    .filter { it.name == "Giant Growth" } shouldHaveSize 1
            }
        }

        test("Giant Growth — invariants hold across targeting flow") {
            startPuzzleFile("puzzles/pump-spell.pzl")
            val creatureIid = humanBattlefieldCreatures().first().first

            assertAccumulatorConsistent("before targeting")
            castSpellByName("Giant Growth").shouldBeTrue()
            selectTargets(listOf(creatureIid))
            passPriority()

            assertAccumulatorConsistent("after targeting flow")
            assertGsIdChain(allMessages, context = "targeting flow")
        }

        test("Giant Growth — multiple spells stack +3/+3 twice") {
            // Dedicated puzzle with 2 Giant Growths + 2 Forests (enough mana for both).
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Giant Growth;Giant Growth
                humanbattlefield=Grizzly Bears;Forest;Forest
                humanlibrary=Forest;Forest;Forest;Forest;Forest
                aibattlefield=Plains
                ailibrary=Plains;Plains;Plains;Plains;Plains
                """,
                name = "Stacking Giant Growth",
            )

            val creatureIid = instanceIdOf("Grizzly Bears")

            castSpellByName("Giant Growth").shouldBeTrue()
            selectTargets(listOf(creatureIid))
            passUntil(maxPasses = 6) { (cardByIid(creatureIid)?.netPower ?: 0) >= 5 }

            castSpellByName("Giant Growth").shouldBeTrue()
            selectTargets(listOf(creatureIid))
            passUntil(maxPasses = 6) { (cardByIid(creatureIid)?.netPower ?: 0) >= 8 }

            // Grizzly Bears 2/2 + (+3/+3) × 2 = 8/8
            val creature = cardByIid(creatureIid).shouldNotBeNull()
            assertSoftly {
                creature.netPower shouldBe 8
                creature.netToughness shouldBe 8
            }
        }

        // ─── Cancel targeting ───────────────────────────────────────────────────

        test("cancel unwinds stack, card back in hand, re-cast succeeds") {
            startPuzzleFile("puzzles/pump-spell.pzl")
            val creatureIid = humanBattlefieldCreatures().first().first

            // Cast → cancel
            val snap = messageSnapshot()
            castSpellByName("Giant Growth").shouldBeTrue()
            cancelAction()

            assertSoftly {
                game().stack.isEmpty.shouldBeTrue()
                human.getZone(ForgeZoneType.Hand).cards
                    .filter { it.name == "Giant Growth" }.shouldNotBeEmpty()
                messagesSince(snap).any { it.hasActionsAvailableReq() }.shouldBeTrue()
            }

            // Re-cast → select → resolve
            castSpellByName("Giant Growth").shouldBeTrue()
            selectTargets(listOf(creatureIid))
            passUntil(maxPasses = 6) { (cardByIid(creatureIid)?.netPower ?: 0) >= 4 }

            cardByIid(creatureIid).shouldNotBeNull().netPower shouldBeGreaterThanOrEqual 4
            assertAccumulatorConsistent("after cancel + re-cast")
        }

        // ─── Lightning Bolt: player + creature targeting ───────────────────────

        test("Lightning Bolt — prompt shape, sourceId, resolve kills opponent") {
            startPuzzleFile("puzzles/bolt-face.pzl")

            val snap = messageSnapshot()
            castSpellByName("Lightning Bolt").shouldBeTrue()

            val msgs = messagesSince(snap)
            val stMsg = msgs.firstOrNull { it.hasSelectTargetsReq() }
            stMsg.shouldNotBeNull()

            val req = stMsg.selectTargetsReq
            val targets = req.targetsList.first().targetsList
            val targetIds = targets.map { it.targetInstanceId }

            // sourceId matches stack iid (post-realloc)
            val gsms = msgs.filter { it.hasGameStateMessage() }.map { it.gameStateMessage }
            val stackZone = gsms.flatMap { it.zonesList }
                .firstOrNull { it.type == ProtoZoneType.Stack }
            stackZone.shouldNotBeNull()
            val stackInstanceId = stackZone.objectInstanceIdsList.firstOrNull()
            stackInstanceId.shouldNotBeNull()

            assertSoftly {
                // Both players legal
                targetIds shouldContain HUMAN_SEAT
                targetIds shouldContain OPPONENT_SEAT

                // Highlights by role
                targets.first { it.targetInstanceId == OPPONENT_SEAT }.highlight shouldBe HighlightType.Hot
                targets.first { it.targetInstanceId == HUMAN_SEAT }.highlight shouldBe HighlightType.Cold
                val creatureTargets = targets.filter { it.targetInstanceId > OPPONENT_SEAT }
                creatureTargets.shouldNotBeEmpty()
                creatureTargets.forEach { it.highlight shouldBe HighlightType.Tepid }

                // Wrapper flags
                stMsg.allowCancel shouldBe AllowCancel.Abort
                stMsg.allowUndo.shouldBeTrue()

                // sourceId = stack iid
                req.sourceId shouldBe stackInstanceId
            }

            // Resolve → opponent at 3 life → game over
            selectTargets(listOf(OPPONENT_SEAT))
            passPriority()

            isGameOver().shouldBeTrue()
            allMessages.filter {
                it.hasGameStateMessage() &&
                    it.gameStateMessage.hasGameInfo() &&
                    it.gameStateMessage.gameInfo.stage == GameStage.GameOver
            }.shouldNotBeEmpty()

            assertAccumulatorConsistent("after bolt targeting + resolve")
        }

        test("Lightning Bolt — cancel then re-cast kills opponent") {
            startPuzzleFile("puzzles/bolt-face.pzl")

            castSpellByName("Lightning Bolt").shouldBeTrue()
            cancelAction()
            game().stack.isEmpty.shouldBeTrue()

            castSpellByName("Lightning Bolt").shouldBeTrue()
            selectTargets(listOf(OPPONENT_SEAT))
            passPriority()

            isGameOver().shouldBeTrue()
        }

        // ─── Two-phase targeting protocol ──────────────────────────────────────

        val twoPhaseBoltState = """
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=3

            humanhand=Lightning Bolt
            humanbattlefield=Mountain
            humanlibrary=Mountain
            ailibrary=Mountain
        """.trimIndent()

        test("two-phase — phase-1 echo re-prompt shows selected target as Unselect") {
            startPuzzle(twoPhaseBoltState, name = "Bolt Conformance")
            val ai = game().registeredPlayers.last()

            castSpellByName("Lightning Bolt").shouldBeTrue()

            val snap = messageSnapshot()
            selectTargetsIterative(listOf(OPPONENT_SEAT))
            val echoMessages = messagesSince(snap)

            val rePromptMsg = echoMessages.firstOrNull { it.hasSelectTargetsReq() }
            rePromptMsg.shouldNotBeNull()
            val targetGroup = rePromptMsg.selectTargetsReq.getTargets(0)

            assertSoftly {
                targetGroup.targetsList shouldHaveSize 1
                targetGroup.targetsList[0].targetInstanceId shouldBe OPPONENT_SEAT
                targetGroup.targetsList[0].legalAction shouldBe SelectAction.Unselect
                targetGroup.selectedTargets shouldBe 1
            }

            submitTargets()
            passUntil(maxPasses = 10) { isGameOver() }

            isGameOver().shouldBeTrue()
            ai.life shouldBe 0
        }

        test("two-phase — phase-1 select alone does not resolve spell") {
            startPuzzle(twoPhaseBoltState, name = "Bolt Two-Phase Gate")
            val ai = game().registeredPlayers.last()

            castSpellByName("Lightning Bolt").shouldBeTrue()

            val phase1Snap = messageSnapshot()
            selectTargetsIterative(listOf(OPPONENT_SEAT))
            val phase1Messages = messagesSince(phase1Snap)

            assertSoftly {
                ai.life shouldBe 3
                isGameOver().shouldBeFalse()
                phase1Messages.any { it.hasSubmitTargetsResp() }.shouldBeFalse()
                phase1Messages.any { it.hasSelectTargetsReq() }.shouldBeTrue()
            }

            val phase2Snap = messageSnapshot()
            submitTargets()
            messagesSince(phase2Snap).any { it.hasSubmitTargetsResp() }.shouldBeTrue()

            passUntil(maxPasses = 10) { isGameOver() }
            isGameOver().shouldBeTrue()
            ai.life shouldBe 0
        }

        // ─── Bite Down: multi-group fight targeting ────────────────────────────

        test("Bite Down — resolution state: damage, destroy, target in GY") {
            startPuzzleFile("puzzles/bite-down.pzl")

            val dealerIid = instanceIdOf("Grizzly Bears", player = human)
            val targetIid = instanceIdOf("Grizzly Bears", player = ai)

            castSpellByName("Bite Down").shouldBeTrue()
            selectTargets(listOf(dealerIid))
            selectTargets(listOf(targetIid))

            val damageAnn = allMessages
                .filter { it.hasGameStateMessage() }
                .flatMap { it.gameStateMessage.annotationsList }
                .firstOrNull { AnnotationType.DamageDealt_af5a in it.typeList }
            damageAnn.shouldNotBeNull()

            assertSoftly {
                // affectorId = dealing creature (not the spell iid)
                damageAnn.affectorId shouldBe dealerIid
                // Damage amount = dealer power (Grizzly Bears = 2)
                damageAnn.detailUint("damage") shouldBe 2
                // affectedIds = reallocated target iid
                damageAnn.affectedIdsCount shouldBe 1
                damageAnn.getAffectedIds(0) shouldBeGreaterThan 0

                // Destroy zone transfer present
                allMessages.firstWithTransferCategory("Destroy").shouldNotBeNull()

                // Bite Down → human GY, Grizzly Bears → ai GY
                human.getZone(ForgeZoneType.Graveyard).cards
                    .filter { it.name == "Bite Down" } shouldHaveSize 1
                ai.getZone(ForgeZoneType.Graveyard).cards
                    .filter { it.name == "Grizzly Bears" }.shouldNotBeEmpty()
            }

            assertAccumulatorConsistent("after Bite Down resolution")
        }

        test("Bite Down — two TargetSpec persistent annotations, cleaned up on resolve") {
            startPuzzleFile("puzzles/bite-down.pzl")

            val dealerIid = instanceIdOf("Grizzly Bears", player = human)
            val targetIid = instanceIdOf("Grizzly Bears", player = ai)

            castSpellByName("Bite Down").shouldBeTrue()
            selectTargets(listOf(dealerIid))
            selectTargets(listOf(targetIid))

            val preResolve = allMessages
                .filter { it.hasGameStateMessage() }
                .flatMap { it.gameStateMessage.persistentAnnotationsList }
                .filter { AnnotationType.TargetSpec in it.typeList }
            preResolve.shouldHaveSize(2)

            val group1 = preResolve.first { it.detailInt("index") == 1 }
            val group2 = preResolve.first { it.detailInt("index") == 2 }
            assertSoftly {
                group1.getAffectedIds(0) shouldNotBe group2.getAffectedIds(0)
                group1.detailInt("abilityGrpId") shouldBe group2.detailInt("abilityGrpId")
                group1.detailInt("abilityGrpId") shouldBeGreaterThan 0
                group1.detailInt("promptParameters") shouldBe group2.detailInt("promptParameters")
            }

            // Force GSM rebuild to trigger upsert cleanup
            passPriority()
            val allDeletedPannIds = allMessages
                .filter { it.hasGameStateMessage() }
                .flatMap { it.gameStateMessage.diffDeletedPersistentAnnotationIdsList }
                .toSet()
            preResolve.map { it.id }.all { it in allDeletedPannIds }.shouldBeTrue()
        }

        // ─── Auto-resolve regression #92 ───────────────────────────────────────

        // TODO: Relocate to an AutoPass consolidation file when one exists —
        // this test is about handlePostCastPrompt / auto-resolve, not targeting.
        test("#92 — non-targeted spell does not prompt Resolve while on stack") {
            startPuzzle(
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanbattlefield=Forest
                humanhand=Llanowar Elves
                humanlibrary=Forest;Forest;Forest;Forest;Forest
                aibattlefield=Mountain
                ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
                """,
                name = "Auto-resolve regression",
                turns = 5,
            )

            // Simulate reference-client settings: auto-resolve own stack effects
            harness.session.autoPassState.update(
                SettingsMessage.newBuilder()
                    .setAutoPassOption(AutoPassOption.ResolveMyStackEffects)
                    .build(),
            )

            castCreature().shouldBeTrue()

            // Before the fix, castCreature() would leave the creature on the stack
            // (ActionsAvailableReq shown as "Resolve" button) instead of auto-resolving.
            assertSoftly {
                humanBattlefieldCreatures().any { it.second == "Llanowar Elves" }.shouldBeTrue()
                game().stack.isEmpty.shouldBeTrue()
            }
        }
    })
