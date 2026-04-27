package leyline.conformance

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.bootstrap.GameBootstrap
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage

/**
 * Mobilize keyword conformance.
 *
 * Mobilize N is a combat-triggered token mechanic. When the source attacks the
 * keyword puts an Ability stack object on the stack; on resolution it creates
 * N tapped attacking 1/1 Warrior tokens with a delayed end-step sacrifice
 * trigger.
 *
 * Forge baseline expands `K:Mobilize:N` into an Attacks trigger plus
 * `DB$ Token | TokenAmount=N | TokenScript=r_1_1_warrior | TokenTapped=True
 * | TokenAttacking=True | AtEOT=Sacrifice`.
 *
 * Wire shape exercised here:
 *   - AbilityInstanceCreated + persistent TriggeringObject when the trigger fires
 *   - ResolutionStart / TokenCreated (xN) / ResolutionComplete on resolution
 *   - per-token EnteredZoneThisTurn, TemporaryPermanent, DelayedTriggerAffectees
 *   - sacrifice ZoneTransfer (category=Sacrifice) at next end step
 *
 * The token is short-lived (it gets sacrificed at end of turn) so the test
 * doesn't poll the live battlefield. Instead it lets MatchSession's auto-pass
 * run through the whole human turn and asserts on the message stream.
 */
class MobilizeKeywordTest :
    FunSpec({

        tags(IntegrationTag)

        var harness: MatchFlowHarness? = null
        afterEach {
            harness?.shutdown()
            harness = null
        }

        beforeSpec {
            // Register the source card and the Warrior token Forge spawns at trigger
            // resolution time. ObjectMapper.resolveTokenGrpId reaches the token via
            // the source's `tokenGrpIds` map (keyed by Forge's spawn-ability index).
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
            val repo = TestCardRegistry.repo
            val warriorGrpId = 300_010
            repo.register(warriorGrpId, "Warrior Token")
            for (cardName in listOf("Reigning Victor", "Mardu Thunderkite", "Dalkovan Packbeasts")) {
                val grpId = TestCardRegistry.ensureCardRegistered(cardName)
                val data = repo.findByGrpId(grpId)!!
                repo.registerData(
                    data.copy(tokenGrpIds = mapOf(0 to warriorGrpId)),
                    cardName,
                )
            }
        }

        val mobilize1Puzzle =
            """
            [metadata]
            Name:Mobilize 1 baseline
            Goal:Win
            Turns:5
            Difficulty:Easy
            Description:Reigning Victor on the battlefield, attack to fire Mobilize 1.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanbattlefield=Reigning Victor
            humanlibrary=Plains;Plains;Plains;Plains;Plains
            ailibrary=Plains;Plains;Plains;Plains;Plains
            """.trimIndent()

        fun List<GREToClientMessage>.allAnnotationTypes(): Set<AnnotationType> {
            val transient =
                asSequence()
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.annotationsList.asSequence() }
                    .flatMap { it.typeList.asSequence() }
            val persistent =
                asSequence()
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.persistentAnnotationsList.asSequence() }
                    .flatMap { it.typeList.asSequence() }
            return (transient + persistent).toSet()
        }

        test("Mobilize 1 trigger emits the full wire shape during attack + resolution") {
            val h = MatchFlowHarness(seed = 42L, validating = false)
            harness = h
            h.connectAndKeepPuzzleText(mobilize1Puzzle)

            val sources = h.humanBattlefieldCreatures().filter { it.second == "Reigning Victor" }
            sources shouldHaveSize 1
            val sourceIid = sources.first().first

            // Drive into combat -> declare attackers via the session priority chain.
            h.passUntil(maxPasses = 30) {
                h.allMessages.any { it.hasDeclareAttackersReq() }
            }
            val req = h.allMessages.lastOrNull { it.hasDeclareAttackersReq() }
                ?: error("never reached DeclareAttackers")
            req.declareAttackersReq.attackersList
                .map { it.attackerInstanceId }
                .contains(sourceIid) shouldBe true

            val snap = h.messageSnapshot()
            h.declareAttackers(listOf(sourceIid))
            // Auto-pass eats the rest of the turn — that's fine, the token only
            // exists between trigger resolution and end step. The annotations
            // get archived in allMessages either way.
            h.passUntil(maxPasses = 30) { h.turn() > 1 || h.isGameOver() }

            val post = h.messagesSince(snap)
            val annotations =
                post
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.annotationsList }
            annotations.shouldNotBeEmpty()
            val types = post.allAnnotationTypes()

            assertSoftly("trigger-half wire shape") {
                types.contains(AnnotationType.AbilityInstanceCreated) shouldBe true
                types.contains(AnnotationType.TriggeringObject) shouldBe true
            }

            assertSoftly("resolution wire shape") {
                types.contains(AnnotationType.ResolutionStart) shouldBe true
                types.contains(AnnotationType.ResolutionComplete) shouldBe true
                types.contains(AnnotationType.AbilityInstanceDeleted) shouldBe true
                types.contains(AnnotationType.TokenCreated) shouldBe true
            }

            assertSoftly("per-token persistent annotations") {
                types.contains(AnnotationType.EnteredZoneThisTurn) shouldBe true
                types.contains(AnnotationType.TemporaryPermanent) shouldBe true
                types.contains(AnnotationType.DelayedTriggerAffectees) shouldBe true
            }

            // Snapshot fidelity: the Mobilize ability ideally appears as a stack
            // gameObject during a priority window. With auto-pass at paceDelayMs=0
            // the trigger fires + resolves between snapshots so the gameObject
            // never surfaces — the event-driven path above is what gives the
            // client the lifecycle annotations. Documented gap, not a hard fail.
        }

        val mobilize3Puzzle =
            """
            [metadata]
            Name:Mobilize 3
            Goal:Win
            Turns:5
            Difficulty:Easy
            Description:Dalkovan Packbeasts on the battlefield, attack to fire Mobilize 3.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanbattlefield=Dalkovan Packbeasts
            humanlibrary=Plains;Plains;Plains;Plains;Plains
            ailibrary=Plains;Plains;Plains;Plains;Plains
            """.trimIndent()

        val twoSourcePuzzle =
            """
            [metadata]
            Name:Two Mobilize sources
            Goal:Win
            Turns:5
            Difficulty:Easy
            Description:Reigning Victor + Mardu Thunderkite both attacking — two Mobilize triggers.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20

            humanbattlefield=Reigning Victor;Mardu Thunderkite
            humanlibrary=Plains;Plains;Plains;Plains;Plains
            ailibrary=Plains;Plains;Plains;Plains;Plains
            """.trimIndent()

        test("Mobilize 3 produces three Warrior tokens") {
            val h = MatchFlowHarness(seed = 42L, validating = false)
            harness = h
            h.connectAndKeepPuzzleText(mobilize3Puzzle)

            val sources = h.humanBattlefieldCreatures().filter { it.second == "Dalkovan Packbeasts" }
            sources shouldHaveSize 1
            val sourceIid = sources.first().first

            h.passUntil(maxPasses = 30) { h.allMessages.any { it.hasDeclareAttackersReq() } }
            val snap = h.messageSnapshot()
            h.declareAttackers(listOf(sourceIid))
            h.passUntil(maxPasses = 30) { h.turn() > 1 || h.isGameOver() }

            val post = h.messagesSince(snap)
            val tokenCreatedCount =
                post.filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.annotationsList }
                    .count { it.typeList.contains(AnnotationType.TokenCreated) }
            tokenCreatedCount shouldBeGreaterThanOrEqual 3
        }

        test("two Mobilize sources both surface AbilityInstanceCreated + TriggeringObject") {
            val h = MatchFlowHarness(seed = 42L, validating = false)
            harness = h
            h.connectAndKeepPuzzleText(twoSourcePuzzle)

            val creatures = h.humanBattlefieldCreatures()
            val attackerIids =
                creatures.filter { it.second == "Reigning Victor" || it.second == "Mardu Thunderkite" }
                    .map { it.first }
            attackerIids shouldHaveSize 2

            h.passUntil(maxPasses = 30) { h.allMessages.any { it.hasDeclareAttackersReq() } }
            val snap = h.messageSnapshot()
            h.declareAttackers(attackerIids)
            h.passUntil(maxPasses = 30) { h.turn() > 1 || h.isGameOver() }

            val post = h.messagesSince(snap)
            // Both triggers should surface — count distinct AbilityInstanceCreated affectedIds.
            val abilityCreated =
                post.filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.annotationsList }
                    .filter { it.typeList.contains(AnnotationType.AbilityInstanceCreated) }
            // affectedIds is the stack ability instanceId.
            val distinctAbilities = abilityCreated.flatMap { it.affectedIdsList }.toSet()
            distinctAbilities.size shouldBeGreaterThanOrEqual 2

            // At least two TriggeringObject pAnns (one per source).
            val triggeringObjects =
                post.filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.persistentAnnotationsList }
                    .filter { it.typeList.contains(AnnotationType.TriggeringObject) }
            triggeringObjects.size shouldBeGreaterThanOrEqual 2
        }

        test("Mobilize 1 cleanup at next end step sacrifices the token") {
            val h = MatchFlowHarness(seed = 42L, validating = false)
            harness = h
            h.connectAndKeepPuzzleText(mobilize1Puzzle)

            val sources = h.humanBattlefieldCreatures().filter { it.second == "Reigning Victor" }
            val sourceIid = sources.first().first

            h.passUntil(maxPasses = 30) { h.allMessages.any { it.hasDeclareAttackersReq() } }
            val snap = h.messageSnapshot()
            h.declareAttackers(listOf(sourceIid))
            // Run the full turn. The token enters at trigger-resolution time and
            // exits at end-step.
            h.passUntil(maxPasses = 30) { h.turn() > 1 || h.isGameOver() }

            val post = h.messagesSince(snap)
            val annotations =
                post
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.annotationsList }

            val sacrifice =
                annotations.filter { ann ->
                    ann.typeList.any { it == AnnotationType.ZoneTransfer_af5a } &&
                        ann.detailsList.any { d -> d.key == "category" && "Sacrifice" in d.valueStringList }
                }
            sacrifice.shouldNotBeEmpty()

            val types = post.allAnnotationTypes()
            assertSoftly("cleanup half") {
                types.contains(AnnotationType.TokenDeleted) shouldBe true
                types.contains(AnnotationType.AbilityInstanceDeleted) shouldBe true
            }
        }
    })
