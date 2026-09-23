package leyline.game.data

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.ForgeCatalogTag
import leyline.IntegrationTag
import leyline.bridge.bootstrap.GameBootstrap
import leyline.testkit.battlefield
import leyline.testkit.hand

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
    })
