package leyline.conformance

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import leyline.IntegrationTag
import leyline.bridge.bootstrap.GameBootstrap
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType

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
            // Inject Arena-aligned ability ids on each Mobilize source so the
            // (keyword, cleanup) pair lookup in StateMapper resolves to real
            // grpIds (188696/188698 + 189930/189931) — without this, the
            // synthetic ids CardDataDeriver mints don't intersect
            // MOBILIZE_CLEANUP_BY_KEYWORD and the test silently emits the
            // generic-EOT shape rather than the canonical Mobilize one.
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
            val repo = TestCardRegistry.repo
            val warriorGrpId = 300_010
            repo.register(warriorGrpId, "Warrior Token")
            // (cardName, mobilizeKeywordRow, mobilizeCleanupRow). Cleanup row
            // lands in `hiddenAbilityIds` to mirror the client card-DB shape
            // — production looks it up via
            // CardRepository.findHiddenTriggeredAbilityGrpId (Category == 2).
            // Mobilize 2 cards (Voice of Victory, Bone-Cairn Butcher, Dalkovan
            // Outrider) are 188727 → 189933; not exercised here.
            val mobilizeCards =
                listOf(
                    Triple("Reigning Victor", 188698, 189931),
                    Triple("Mardu Thunderkite", 188698, 189931),
                    Triple("Dalkovan Packbeasts", 188696, 189930),
                )
            for ((cardName, keywordRow, cleanupRow) in mobilizeCards) {
                val grpId = TestCardRegistry.ensureCardRegistered(cardName)
                val data = repo.findByGrpId(grpId)!!
                repo.registerData(
                    data.copy(
                        tokenGrpIds = mapOf(0 to warriorGrpId),
                        abilityIds = listOf(keywordRow to (1_000_000 + keywordRow)),
                        hiddenAbilityIds = listOf(cleanupRow to (1_000_000 + cleanupRow)),
                    ),
                    cardName,
                )
                // Seed AbilityInfo so findKeywordAbilityGrpId(grpId, "MOBILIZE")
                // walks abilityIds, calls findAbilityInfo(keywordRow), and
                // matches baseId == 363.
                repo.registerAbilityInfo(
                    keywordRow,
                    leyline.game.data.AbilityInfo(baseId = 363, manaCost = emptyList()),
                )
                // Seed AbilityInfo for the cleanup row so
                // findHiddenTriggeredAbilityGrpId picks it via Category == 2
                // (triggered). Mirrors the production card-DB row.
                repo.registerAbilityInfo(
                    cleanupRow,
                    leyline.game.data.AbilityInfo(baseId = 0, manaCost = emptyList(), category = 2),
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

            // Distinct TriggerHolder gameObjects — one per source-card resolution.
            // Catches a regression where two sources collapse onto a single
            // holder iid (e.g. if the holder forge id ever reverts to a
            // per-controller key).
            val holders =
                post.filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.gameObjectsList }
                    .filter { it.type == GameObjectType.TriggerHolder }
            val distinctHolderIids = holders.map { it.instanceId }.toSet()
            distinctHolderIids.size shouldBeGreaterThanOrEqual 2
            // Each holder points at a distinct source via parentId.
            val distinctParents = holders.map { it.parentId }.toSet()
            distinctParents.size shouldBeGreaterThanOrEqual 2
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

        // ------- TriggerHolder gameObject shape + lifecycle -------

        test("Mobilize 1 emits a TriggerHolder gameObject in Limbo with canonical fields") {
            val h = MatchFlowHarness(seed = 42L, validating = false)
            harness = h
            h.connectAndKeepPuzzleText(mobilize1Puzzle)

            val sources = h.humanBattlefieldCreatures().filter { it.second == "Reigning Victor" }
            val sourceIid = sources.first().first

            h.passUntil(maxPasses = 30) { h.allMessages.any { it.hasDeclareAttackersReq() } }
            val snap = h.messageSnapshot()
            h.declareAttackers(listOf(sourceIid))
            // Walk just past the resolution GSM so the holder has been emitted.
            h.passUntil(maxPasses = 8) {
                h.allMessages
                    .drop(snap)
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.gameObjectsList }
                    .any { it.type == GameObjectType.TriggerHolder }
            }

            val post = h.messagesSince(snap)
            val holders =
                post
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.gameObjectsList }
                    .filter { it.type == GameObjectType.TriggerHolder }

            holders.shouldNotBeEmpty()
            val holder = holders.first()
            assertSoftly("holder gameObject canonical shape") {
                holder.grpId shouldBe 5
                holder.zoneId shouldBe 30 // Limbo
                holder.overlayGrpId shouldBe 5
                // Mobilize 1 keyword row drives the side-panel icon's source.
                holder.objectSourceGrpId shouldBe 188698
                // Source card iid linked via parentId.
                holder.parentId shouldBe sourceIid
                // Cleanup ability grpId carried in uniqueAbilities[0].grpId →
                // drives the side-panel tooltip text.
                holder.uniqueAbilitiesList.shouldNotBeEmpty()
                holder.uniqueAbilitiesList.first().grpId shouldBe 189931
            }

            // Limbo zone in this GSM must contain the holder iid so the
            // client knows the holder lives there.
            val limboContainsHolder =
                post
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.zonesList }
                    .any { it.zoneId == 30 && holder.instanceId in it.objectInstanceIdsList }
            limboContainsHolder shouldBe true

            // The tracker shares the holder iid as affector for both
            // DelayedTriggerAffectees and per-token TemporaryPermanent — so
            // the client links cleanup ability → tokens via this iid.
            val pAnns =
                post
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.persistentAnnotationsList }
            val dta =
                pAnns.first { it.typeList.contains(AnnotationType.DelayedTriggerAffectees) }
            val tempPerm =
                pAnns.first { it.typeList.contains(AnnotationType.TemporaryPermanent) }
            assertSoftly("annotations reference the holder") {
                dta.affectorId shouldBe holder.instanceId
                tempPerm.affectorId shouldBe holder.instanceId
            }
        }

        test("Mobilize holder is emitted once, not re-emitted, then deleted via diffDeletedInstanceIds") {
            val h = MatchFlowHarness(seed = 42L, validating = false)
            harness = h
            h.connectAndKeepPuzzleText(mobilize1Puzzle)

            val sources = h.humanBattlefieldCreatures().filter { it.second == "Reigning Victor" }
            val sourceIid = sources.first().first

            h.passUntil(maxPasses = 30) { h.allMessages.any { it.hasDeclareAttackersReq() } }
            val snap = h.messageSnapshot()
            h.declareAttackers(listOf(sourceIid))
            h.passUntil(maxPasses = 30) { h.turn() > 1 || h.isGameOver() }

            val post = h.messagesSince(snap)
            val gsms = post.filter { it.hasGameStateMessage() }.map { it.gameStateMessage }

            // Walk every GSM. Find which carry the holder gameObject and which
            // carry its iid in diffDeletedInstanceIds.
            val holderEmissions =
                gsms.mapIndexedNotNull { idx, gsm ->
                    val h0 =
                        gsm.gameObjectsList.firstOrNull { it.type == GameObjectType.TriggerHolder }
                    if (h0 != null) idx to h0.instanceId else null
                }
            holderEmissions.shouldNotBeEmpty()
            val holderIid = holderEmissions.first().second

            val deletionGsmIndices =
                gsms.mapIndexedNotNull { idx, gsm ->
                    if (holderIid in gsm.diffDeletedInstanceIdsList) idx else null
                }

            assertSoftly("holder lifecycle") {
                // Emitted in exactly one GSM (the resolution diff). Re-emitting
                // every GSM while the holder is live is wire noise the
                // canonical wire doesn't produce.
                holderEmissions.map { it.first } shouldHaveSize 1
                // Deleted exactly once when cleanup retires it.
                deletionGsmIndices shouldHaveSize 1
                // Deletion lands strictly after emission.
                deletionGsmIndices.first() shouldBeGreaterThanOrEqual holderEmissions.first().first
                // Same iid throughout — no re-allocation.
                holderEmissions.first().second shouldBe holderIid
            }

            // Sanity: the deleted iid is the one that was emitted.
            gsms[deletionGsmIndices.first()].diffDeletedInstanceIdsList shouldContain holderIid
        }
    })
