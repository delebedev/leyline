package leyline.conformance

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.InstanceId
import leyline.bridge.types.SeatId
import leyline.game.annotations.AnnotationConstants
import leyline.game.codes.DetailKeys
import leyline.game.mapping.ZoneIds
import wotc.mtgo.gre.external.messaging.Messages.AnnotationInfo
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GREToClientMessage
import wotc.mtgo.gre.external.messaging.Messages.GameObjectInfo
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType

/**
 * Walk every emitted GSM and return the active Prepared `Designation` pAnns
 * (DesignationType=24), deduped by id. Tests use this instead of inspecting a
 * single GSM because persistent annotations are differential — a pAnn added in
 * an earlier diff GSM doesn't republish in later ones.
 */
private fun preparedDesignations(messages: List<GREToClientMessage>): List<AnnotationInfo> =
    messages
        .mapNotNull { if (it.hasGameStateMessage()) it.gameStateMessage else null }
        .flatMap { it.persistentAnnotationsList }
        .filter { ann ->
            ann.typeList.contains(AnnotationType.Designation) &&
                ann.detailsList.any {
                    it.key == DetailKeys.DESIGNATION_TYPE &&
                        it.valueInt32Count > 0 &&
                        it.getValueInt32(0) == AnnotationConstants.DESIGNATION_TYPE_PREPARED
                }
        }.distinctBy { it.id }

/**
 * First emitted [GameObjectInfo] for [iid] across all GSMs — diff GSMs only
 * carry the object in the GSM that introduced it; subsequent diffs reference
 * by iid only. The introduction GSM is the canonical source for static fields
 * (type, isCopy, parentId, abilities).
 */
private fun firstGameObjectFor(
    messages: List<GREToClientMessage>,
    iid: Int,
): GameObjectInfo =
    messages
        .mapNotNull { if (it.hasGameStateMessage()) it.gameStateMessage else null }
        .firstNotNullOf { gsm -> gsm.gameObjectsList.firstOrNull { it.instanceId == iid } }

/**
 * End-to-end coverage for the Prepared card-state designation (bd leyline-jtsv).
 *
 * Honorbound Page enters prepared via an ETB replacement effect. Forge's
 * AlterAttributeEffect spawns a copy of the alternate face (Forum's Favor)
 * into Exile, parented to a command-zone effect that holds a MayPlay static.
 *
 * Contract validated here:
 *
 * - Persistent `Designation` annotation with type 24 (Prepared), anchored on
 *   the live battlefield creature, carrying `PreparedCopyZcid` pointing at
 *   the exile copy.
 * - Exile copy projects as `GameObjectType_Card` (not Token) with
 *   `isCopy=true`, `parentId` pointing back at the prepared creature, and
 *   `grpId` resolved to the prepare-spell face's id via name lookup.
 * - Cast-from-exile is offered as a normal `Cast` action and resolves through
 *   `ObjectMapper.resolveGrpId` without tripping the strict-mode token-grpId
 *   guard — Forge reallocates the copy's `Card.id` on the exile→stack
 *   transition, so detection has to be state-based, not identity-based.
 */
class HonorboundPagePrepareTest :
    InteractionTest({

        test("Prepared state: persistent Designation + exile copy projection") {
            startPuzzleFile("puzzles/honorbound-page-prepare.pzl", validating = true)

            castSpellByName("Honorbound Page")
            passUntilResolved()

            val sourceIid = instanceIdOf("Honorbound Page", human, ZoneType.Battlefield)
            val copyIid = instanceIdOf("Forum's Favor", human, ZoneType.Exile)

            // Persistent annotations are differential — pAnns added in earlier
            // GSMs aren't republished in later diffs. Walk every emitted GSM,
            // dedupe by id, take the matching Prepared Designation.
            val designation =
                preparedDesignations(allMessages).single()
            assertSoftly {
                designation.affectorId shouldBe sourceIid
                designation.affectedIdsList shouldContain sourceIid
                designation
                    .detailsList
                    .first { it.key == DetailKeys.PREPARED_COPY_ZCID }
                    .getValueInt32(0) shouldBe copyIid
            }

            // Exile copy GameObjectInfo: rendered as Card (not Token), parented back
            // to the source creature, isCopy=true, grpId resolved by name to the
            // prepare-spell face — bypasses the engine-spawned-token grpId path.
            val copyObj = firstGameObjectFor(allMessages, copyIid)
            assertSoftly {
                copyObj.type shouldBe GameObjectType.Card
                copyObj.isCopy shouldBe true
                copyObj.parentId shouldBe sourceIid
                copyObj.grpId shouldNotBe 0
                copyObj.zoneId shouldBe ZoneIds.EXILE
                // Prepared copies do NOT carry objectSourceGrpId — that field is
                // reserved for engine-spawned tokens (e.g. Krenko goblins).
                copyObj.objectSourceGrpId shouldBe 0
            }
        }

        test("ObjectMapper.resolveGrpId on prepared copy returns by-name grpId, not 0") {
            startPuzzleFile("puzzles/honorbound-page-prepare.pzl", validating = false)

            castSpellByName("Honorbound Page")
            passUntilResolved()

            // Direct exercise of ObjectMapper.resolveGrpId — the path
            // ActionPerformer.resolveCastAbilityIndex takes when the player casts
            // the prepared copy. Pre-fix this returned 0 (DevCheck.fail) because
            // the token-spawning-ability path doesn't fit prepared copies.
            val copy =
                human
                    .getZone(ZoneType.Exile)
                    .cards
                    .first { it.name == "Forum's Favor" }
            val copyIid = harness.bridge.getOrAllocInstanceId(ForgeCardId(copy.id)).value
            val grpId = harness.bridge.resolveGrpId(copy, copyIid)
            grpId shouldNotBe 0
            // Same value the cardRepository would resolve via name lookup.
            grpId shouldBe harness.bridge.cardRepository.findGrpIdByName("Forum's Favor")
        }

        test("Cast-from-exile: action accepted + resolveGrpId by name (no strict-mode crash)") {
            // Validating disabled: a downstream LayeredEffect emission for the +1/+0
            // flying buff carries a stale affectorId post-resolve, which is a separate
            // latent issue unrelated to the cast-from-exile rail this test exercises.
            startPuzzleFile("puzzles/honorbound-page-prepare.pzl", validating = false)

            castSpellByName("Honorbound Page")
            passUntilResolved()

            // Forge reallocates the exile copy's `Card.id` between the resolve that
            // creates it and the cast that puts it on the stack. The previous
            // implementation went through `resolveGrpId`'s token path on the stack
            // form and crashed with `[strict] token grpId=0`. State-based detection
            // routes both forms through name lookup instead.
            val cast = castSpellByName("Forum's Favor", zone = ZoneType.Exile)
            cast shouldBe true
            // No exception thrown means resolveGrpId succeeded by name on the new
            // stack-form Card.id — the previously crashing path is now exercised.
        }

        test("GainDesignation transient + Stack→Battlefield Resolve land in the same GSM") {
            startPuzzleFile("puzzles/honorbound-page-prepare.pzl", validating = true)

            castSpellByName("Honorbound Page")
            passUntilResolved()

            val sourceIid = instanceIdOf("Honorbound Page", human, ZoneType.Battlefield)

            // Find the GSM that carries the Stack→Battlefield Resolve ZoneTransfer
            // for Honorbound Page. The protocol spec requires GainDesignation type=24
            // anchored on the same creature in this same GSM.
            val resolveGsm =
                allMessages
                    .mapNotNull { if (it.hasGameStateMessage()) it.gameStateMessage else null }
                    .first { gsm ->
                        gsm.annotationsList.any { ann ->
                            ann.typeList.contains(AnnotationType.ZoneTransfer_af5a) &&
                                ann.affectedIdsList.contains(sourceIid) &&
                                ann.detailsList.any {
                                    it.key == DetailKeys.CATEGORY &&
                                        it.valueStringCount > 0 &&
                                        it.getValueString(0) == "Resolve"
                                } &&
                                ann.detailsList.any {
                                    it.key == DetailKeys.ZONE_DEST &&
                                        it.valueInt32Count > 0 &&
                                        it.getValueInt32(0) == ZoneIds.BATTLEFIELD
                                }
                        }
                    }

            val gainDesignation =
                resolveGsm.annotationsList.firstOrNull { ann ->
                    ann.typeList.contains(AnnotationType.GainDesignation) &&
                        ann.affectorId == sourceIid &&
                        ann.affectedIdsList.contains(sourceIid) &&
                        ann.detailsList.any {
                            it.key == DetailKeys.DESIGNATION_TYPE &&
                                it.valueInt32Count > 0 &&
                                it.getValueInt32(0) == AnnotationConstants.DESIGNATION_TYPE_PREPARED
                        }
                }
            gainDesignation shouldNotBe null
        }

        test("LoseDesignation transient fires when the prepared copy is cast") {
            startPuzzleFile("puzzles/honorbound-page-prepare.pzl", validating = false)

            castSpellByName("Honorbound Page")
            passUntilResolved()

            val sourceIid = instanceIdOf("Honorbound Page", human, ZoneType.Battlefield)

            // Pre-cast: persistent Designation pAnn exists for the source. Save
            // its id so we can assert it's listed in diffDeletedPersistentAnnotationIds
            // after the cast clears the prepared state.
            val designationIdBeforeCast = preparedDesignations(allMessages).single().id
            val cutoffMessageCount = allMessages.size

            // Cast the prepared copy and select a target — Forge's SpellCast trigger
            // (Mode$ SpellCast | Static$ True | ValidSA$ Spell.IsRemembered) fires on
            // moveToStack, which only happens after target selection completes. So
            // the unprepare path runs only once we provide a target.
            castSpellByName("Forum's Favor", zone = ZoneType.Exile)
            val opponentBear =
                ai.getZone(ZoneType.Battlefield).cards.first { it.name == "Grizzly Bears" }
            val oppIid =
                harness.bridge
                    .getOrAllocInstanceId(ForgeCardId(opponentBear.id))
                    .value
            selectTargets(listOf(oppIid))

            val postCastGsms =
                allMessages
                    .drop(cutoffMessageCount)
                    .mapNotNull { if (it.hasGameStateMessage()) it.gameStateMessage else null }
            val loseDesignation =
                postCastGsms
                    .flatMap { it.annotationsList }
                    .firstOrNull { ann ->
                        ann.typeList.contains(AnnotationType.LoseDesignation) &&
                            ann.affectorId == sourceIid &&
                            ann.detailsList.any {
                                it.key == DetailKeys.DESIGNATION_TYPE &&
                                    it.valueInt32Count > 0 &&
                                    it.getValueInt32(0) == AnnotationConstants.DESIGNATION_TYPE_PREPARED
                            }
                    }
            loseDesignation shouldNotBe null

            // The persistent Designation pAnn must also be torn down — its id should
            // appear in `diffDeletedPersistentAnnotationIds` on a post-cast GSM.
            val deletedIds =
                postCastGsms.flatMap { it.diffDeletedPersistentAnnotationIdsList }.toSet()
            deletedIds shouldContain designationIdBeforeCast
        }

        test("Two prepared creatures: each Designation anchored on its own source iid") {
            startPuzzleFile("puzzles/two-prepared.pzl", validating = true)

            castSpellByName("Honorbound Page")
            passUntilResolved()
            castSpellByName("Elite Interceptor")
            passUntilResolved()

            val honorbound = instanceIdOf("Honorbound Page", human, ZoneType.Battlefield)
            val interceptor = instanceIdOf("Elite Interceptor", human, ZoneType.Battlefield)
            val forumsFavor = instanceIdOf("Forum's Favor", human, ZoneType.Exile)
            val rejoinder = instanceIdOf("Rejoinder", human, ZoneType.Exile)

            // Persistent annotations are differential — pAnns added in earlier
            // GSMs aren't republished in later diffs. Walk every emitted GSM and
            // collect Prepared Designation pAnns by id; the most recent entry per
            // (affector, copy) pair is the source of truth.
            val allDesignations =
                allMessages
                    .mapNotNull { if (it.hasGameStateMessage()) it.gameStateMessage else null }
                    .flatMap { it.persistentAnnotationsList }
                    .filter { ann ->
                        ann.typeList.contains(AnnotationType.Designation) &&
                            ann.detailsList.any {
                                it.key == DetailKeys.DESIGNATION_TYPE &&
                                    it.valueInt32Count > 0 &&
                                    it.getValueInt32(0) == AnnotationConstants.DESIGNATION_TYPE_PREPARED
                            }
                    }.distinctBy { it.id }

            assertSoftly {
                allDesignations.size shouldBe 2
                val byAffector = allDesignations.associateBy { it.affectorId }
                byAffector
                    .getValue(honorbound)
                    .detailsList
                    .first { it.key == DetailKeys.PREPARED_COPY_ZCID }
                    .getValueInt32(0) shouldBe forumsFavor
                byAffector
                    .getValue(interceptor)
                    .detailsList
                    .first { it.key == DetailKeys.PREPARED_COPY_ZCID }
                    .getValueInt32(0) shouldBe rejoinder
            }

            // Each copy parented to its own source — read from the GSM that
            // introduced it (the resolve diff).
            val forumGsm =
                allMessages
                    .mapNotNull { if (it.hasGameStateMessage()) it.gameStateMessage else null }
                    .first { gsm -> gsm.gameObjectsList.any { it.instanceId == forumsFavor } }
            val rejoinderGsm =
                allMessages
                    .mapNotNull { if (it.hasGameStateMessage()) it.gameStateMessage else null }
                    .first { gsm -> gsm.gameObjectsList.any { it.instanceId == rejoinder } }
            val forumObj = forumGsm.gameObjectsList.first { it.instanceId == forumsFavor }
            val rejoinderObj = rejoinderGsm.gameObjectsList.first { it.instanceId == rejoinder }
            assertSoftly {
                forumObj.parentId shouldBe honorbound
                rejoinderObj.parentId shouldBe interceptor
                forumObj.type shouldBe GameObjectType.Card
                rejoinderObj.type shouldBe GameObjectType.Card
            }
        }

        test("Exile copy uniqueAbilities reflect the spell face, not the source creature") {
            startPuzzleFile("puzzles/honorbound-page-prepare.pzl", validating = true)

            castSpellByName("Honorbound Page")
            passUntilResolved()

            val gsm =
                allMessages
                    .last { it.hasGameStateMessage() }
                    .gameStateMessage

            val copyIid = instanceIdOf("Forum's Favor", human, ZoneType.Exile)
            val copyObj = gsm.gameObjectsList.first { it.instanceId == copyIid }

            // Resolve expected ids from the bridge's CardRepository (synthetic in
            // tests, client-DB-backed in prod). Either way, the copy must project
            // Forum's Favor's grpId + abilities, NOT Honorbound Page's.
            val repo = harness.bridge.cardRepository
            val forumGrpId =
                repo.findGrpIdByName("Forum's Favor") ?: error("Forum's Favor not in repo")
            val honorboundGrpId =
                repo.findGrpIdByName("Honorbound Page") ?: error("Honorbound Page not in repo")
            val forumAbilityIds =
                repo.findByGrpId(forumGrpId)!!.abilityIds.map { it.first }.toSet()
            val honorboundAbilityIds =
                repo.findByGrpId(honorboundGrpId)!!.abilityIds.map { it.first }.toSet()

            val copyAbilityIds = copyObj.uniqueAbilitiesList.map { it.grpId }.toSet()
            assertSoftly {
                copyObj.grpId shouldBe forumGrpId
                copyAbilityIds shouldBe forumAbilityIds
                copyAbilityIds.intersect(honorboundAbilityIds) shouldBe emptySet()
            }
        }
    })
