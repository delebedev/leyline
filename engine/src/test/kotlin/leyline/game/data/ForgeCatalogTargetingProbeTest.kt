package leyline.game.data

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.ForgeCatalogTag
import leyline.IntegrationTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.bridge.types.StaticChoiceIds
import leyline.game.mapping.ZoneIds
import leyline.testkit.annotationsOfType
import leyline.testkit.battlefield
import leyline.testkit.detailInt
import leyline.testkit.graveyard
import leyline.testkit.hand
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType
import wotc.mtgo.gre.external.messaging.Messages.GameObjectType
import wotc.mtgo.gre.external.messaging.Messages.StaticList

class ForgeCatalogTargetingProbeTest :
    FunSpec({
        tags(IntegrationTag, ForgeCatalogTag)
        beforeSpec { GameBootstrap.initializeCardDatabase(quiet = true) }
        test("mixed-zone spell asks for a nonfirst permanent target") {
            forgeCatalogProbe(
                "mixed-zone-target",
                "humanhand=Crystal Spray\nhumanbattlefield=Island;Grizzly Bears;Island;Island",
            ) {
                castSpellByName("Crystal Spray").shouldBeTrue()
                val prompt = allMessages.lastOrNull { it.hasSelectTargetsReq() }
                checkNotNull(prompt) { "Crystal Spray must request its target before choosing text" }
                val target = human.battlefield.iid("Grizzly Bears")
                check(
                    prompt.selectTargetsReq.targetsList
                        .flatMap { it.targetsList }
                        .any { it.targetInstanceId == target },
                )
                selectTargets(listOf(target))
                val ability = game().stack.firstOrNull { it.sourceCard.name == "Crystal Spray" }?.spellAbility
                checkNotNull(ability)
                ability.targetCard.name shouldBe "Grizzly Bears"
            }
        }
        test("mixed-zone targeting cancellation leaves the spell and mana reusable") {
            forgeCatalogProbe("mixed-zone-cancel", "humanhand=Crystal Spray\nhumanbattlefield=Island;Grizzly Bears;Island;Island") {
                castSpellByName("Crystal Spray").shouldBeTrue()
                check(allMessages.any { it.hasSelectTargetsReq() })
                cancelAction()
                assertSoftly {
                    human.hand.cards.count { it.name == "Crystal Spray" } shouldBe 1
                    human.battlefield.cards.count { it.isTapped } shouldBe 0
                }
                castSpellByName("Crystal Spray").shouldBeTrue()
                check(allMessages.last { it.hasSelectTargetsReq() }.selectTargetsReq.targetsCount > 0)
            }
        }
        test("Specialize selects a color and publishes the resulting form identity") {
            forgeCatalogProbe(
                "specialize",
                puzzleFile = "data/puzzles/specialize-ambergris.pzl",
            ) { repo ->
                val base = requireNotNull(repo.findGrpIdByName("Ambergris, Citadel Agent"))
                val tyranny = requireNotNull(repo.findGrpIdByNameAnyFace("Ambergris, Agent of Tyranny"))
                val ambergris = human.battlefield.card("Ambergris, Citadel Agent")
                val ambergrisIid = human.battlefield.iid(ambergris)
                activateAbility("Ambergris, Citadel Agent").shouldBeTrue()
                val colorReq = lastSelectNReq()
                colorReq.staticList shouldBe StaticList.Colors
                respondToSelectN(listOf(requireNotNull(StaticChoiceIds.colorIdForName("Black"))))
                val discardReq = lastSelectNReq()
                respondToSelectN(listOf(findInstanceId(discardReq.idsList, "Swamp")))
                passUntil(10) { ambergris.name == "Ambergris, Agent of Tyranny" }.shouldBeTrue()
                val formObjects =
                    allMessages
                        .filter { it.hasGameStateMessage() }
                        .flatMap { it.gameStateMessage.gameObjectsList }
                        .filter { it.instanceId == ambergrisIid }
                assertSoftly {
                    ambergris.netPower shouldBe 4
                    ambergris.netToughness shouldBe 3
                    formObjects.last().grpId shouldBe tyranny
                    repo.findGrpIdByName("Ambergris, Agent of Tyranny") shouldBe base
                    human.graveyard.cards.count { it.name == "Swamp" } shouldBe 1
                }

                val triggerGrpId =
                    requireNotNull(repo.findByGrpId(tyranny))
                        .abilityIds
                        .single { repo.findAbilityInfo(it.first)?.category == 2 }
                        .first
                val attackStart = messageSnapshot()
                holdNextOptionalAction()
                passUntil(10) { allMessages.any { it.hasDeclareAttackersReq() } }.shouldBeTrue()
                declareAttackers(listOf(ambergrisIid))
                passUntil(5) { allMessages.any { it.hasOptionalActionMessage() } }.shouldBeTrue()
                respondToOptionalAction(accept = true)
                passUntil(5) { allMessages.any { it.hasSelectTargetsReq() } }.shouldBeTrue()
                selectTargets(listOf(ai.battlefield.iid("Grizzly Bears")))
                passUntil(10) { ai.graveyard.cards.any { it.name == "Grizzly Bears" } }.shouldBeTrue()
                assertSoftly {
                    ai.graveyard.cards.count { it.name == "Grizzly Bears" } shouldBe 1
                    human.graveyard.cards.count { it.name == "Walking Corpse" } shouldBe 1
                    human.hand.cards.count { it.name == "Unsummon" } shouldBe 2
                }

                val attackMessages = messagesSince(attackStart)
                val triggerObjects =
                    attackMessages
                        .filter { it.hasGameStateMessage() }
                        .flatMap { it.gameStateMessage.gameObjectsList }
                        .filter { it.type == GameObjectType.Ability && it.parentId == ambergrisIid }
                        .distinctBy { it.instanceId }
                assertSoftly {
                    triggerObjects.size shouldBe 2
                    triggerObjects.map { it.grpId } shouldBe listOf(triggerGrpId, triggerGrpId)
                    triggerObjects.map { it.objectSourceGrpId } shouldBe listOf(tyranny, tyranny)
                    for (type in listOf(AnnotationType.ResolutionStart, AnnotationType.ResolutionComplete)) {
                        val resolutions =
                            attackMessages
                                .annotationsOfType(type)
                                .filter { row -> triggerObjects.any { it.instanceId == row.affectorId } }
                        resolutions.size shouldBe 2
                        resolutions.map { it.detailInt("grpid") } shouldBe listOf(triggerGrpId, triggerGrpId)
                    }
                }

                val unsummon = requireNotNull(repo.findGrpIdByName("Unsummon"))
                passUntil(10) {
                    allMessages.lastOrNull { it.hasActionsAvailableReq() }?.actionsAvailableReq?.actionsList?.any {
                        it.actionType == ActionType.Cast && it.grpId == unsummon
                    } == true
                }.shouldBeTrue()
                castSpellByName("Unsummon").shouldBeTrue()
                selectTargets(listOf(ambergrisIid))
                passUntil(10) { human.hand.cards.any { it.name == "Ambergris, Agent of Tyranny" } }.shouldBeTrue()
                val movedAmbergris = human.hand.card("Ambergris, Agent of Tyranny")
                val movedAmbergrisIid = human.hand.iid(movedAmbergris)
                val movedObject =
                    allMessages
                        .filter { it.hasGameStateMessage() }
                        .flatMap { it.gameStateMessage.gameObjectsList }
                        .last { it.instanceId == movedAmbergrisIid && it.zoneId == ZoneIds.P1_HAND }
                assertSoftly {
                    movedObject.grpId shouldBe tyranny
                    movedAmbergrisIid shouldNotBe ambergrisIid
                }
            }
        }
    })
