package leyline.game.data

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import leyline.ForgeCatalogTag
import leyline.IntegrationTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.testkit.battlefield
import leyline.testkit.exile
import leyline.testkit.graveyard
import leyline.testkit.library
import leyline.tooling.headless.HeadlessResponseMode
import leyline.tooling.headless.TestCardRegistry
import java.nio.file.Files

class ForgeCatalogModalProbeTest :
    FunSpec({
        tags(IntegrationTag, ForgeCatalogTag)
        timeout = 600_000L
        beforeSpec { GameBootstrap.initializeCardDatabase(quiet = true) }
        afterEach { TestCardRegistry.repo.registeredCount shouldBe 0 }

        test("subability modal choices retain ordered identities and canonical deck lookup") {
            val repo = ForgeCardRepository.open()
            val name = "Dance with Calamity"
            val cardId = requireNotNull(repo.findDeckGrpIdByName(name))
            val options = requireNotNull(repo.lookupModalOptions(cardId))
            assertSoftly {
                options.parentGrpId shouldBe requireNotNull(repo.findByGrpId(cardId)).abilityIds.single().first
                options.childGrpIds.distinct().size shouldBe 2
                options.childGrpIds.map { repo.findAbilityLocalization(it)?.text } shouldBe
                    listOf(
                        "As many times as you choose, you may exile the top card of your library.",
                        "If the total mana value of the cards exiled this way is 13 or less, " +
                            "you may cast any number of spells from among those cards without paying their mana costs.",
                    )
                val cold = ForgeCardRepository.open()
                cold.findDeckGrpIdByName(name) shouldBe cardId
                cold.lookupModalOptions(cardId) shouldBe options
                val index = Files.createTempFile("subability-modal-index-", ".json")
                val archiveHash = "a".repeat(64)
                ForgeCardRepository.writeCatalogIndex(index, archiveHash)
                val indexed = ForgeCardRepository.open(index, archiveHash)
                indexed.findDeckGrpIdByName(name) shouldBe cardId
                indexed.lookupModalOptions(cardId) shouldBe options
                indexed.catalogVersion shouldBe repo.catalogVersion
                repo.catalogIdentityIds
                    .filterKeys { it.startsWith("$name:face:0:mode:") }
                    .values
                    .toSet() shouldBe options.childGrpIds.toSet()
            }
        }

        for ((exileCount, castExiled) in listOf(1 to true, 1 to false, 2 to false)) {
            test("subability generic choice exiles $exileCount cards and ${if (castExiled) "casts one" else "declines casting"}") {
                forgeCatalogProbe(
                    "subability-modal-choice-$exileCount-$castExiled",
                    "humanhand=Dance with Calamity;Ornithopter\n" +
                        "humanlibrary=Ornithopter;Ornithopter;Ornithopter;Ornithopter;Ornithopter\n" +
                        "humanbattlefield=Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain;Mountain",
                    responseMode = HeadlessResponseMode.PolicyVisible,
                ) { repo ->
                    val cardId = requireNotNull(repo.findDeckGrpIdByName("Dance with Calamity"))
                    val cto = castSpellUntilCastingTimeOptionsReq("Dance with Calamity").getCastingTimeOptionReq(0)
                    val modal = cto.modalReq
                    val options = requireNotNull(repo.lookupModalOptions(cardId))
                    assertSoftly {
                        modal.minSel shouldBe 1
                        modal.maxSel shouldBe 1
                        modal.modalOptionsList.map { it.grpId } shouldBe options.childGrpIds
                    }
                    val before = messageSnapshot()
                    respondModalChoice(listOf(modal.getModalOptions(0).grpId))
                    human.exile.cards.size shouldBe 1
                    human.library.cards.size shouldBe 4
                    val firstRepeat = allMessages.last { it.hasOptionalActionMessage() }
                    firstRepeat.optionalActionMessage.sourceId shouldBe cto.affectedId
                    if (exileCount == 2) {
                        respondToOptionalAction(accept = true)
                        val nextRepeat = allMessages.last { it.hasOptionalActionMessage() }
                        nextRepeat.msgId shouldBeGreaterThan firstRepeat.msgId
                        nextRepeat.optionalActionMessage.sourceId shouldBe firstRepeat.optionalActionMessage.sourceId
                    }
                    human.exile.cards.size shouldBe exileCount
                    human.library.cards.size shouldBe 5 - exileCount
                    respondToOptionalAction(accept = false)
                    if (exileCount == 2) {
                        allMessages.last { it.hasSelectNReq() }.selectNReq.minSel shouldBe 0
                        respondToSelectN(emptyList())
                    } else {
                        val play = allMessages.last { it.hasOptionalActionMessage() }
                        play.msgId shouldBeGreaterThan firstRepeat.msgId
                        play.optionalActionMessage.sourceId shouldBe human.exile.iid("Ornithopter")
                        respondToOptionalAction(accept = castExiled)
                    }
                    passUntilResolved(maxPasses = 8)
                    assertSoftly {
                        human.graveyard.card("Dance with Calamity").name shouldBe "Dance with Calamity"
                        human.battlefield.cards.count { it.name == "Ornithopter" } shouldBe if (castExiled) 1 else 0
                        human.exile.cards.size shouldBe exileCount - if (castExiled) 1 else 0
                        game().stackZone.size() shouldBe 0
                        game().isGameOver shouldBe false
                        messagesSince(before).any { it.hasActionsAvailableReq() }.shouldBeTrue()
                    }
                }
            }
        }
    })
