package leyline.bridge.coord

import forge.game.ability.AbilityFactory
import forge.game.card.Card
import forge.game.spellability.AbilitySub
import forge.game.spellability.SpellAbility
import forge.model.FModel
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import leyline.bridge.bootstrap.GameBootstrap

class SuspectChoiceClassifierTest :
    FunSpec({
        tags(UnitTag)

        beforeSpec { GameBootstrap.initializeCardDatabase(quiet = true) }

        test("classifies suspect choice by effect shape without using the host card name") {
            val host = forgeCard("Frantic Scapegoat")
            host.setName("Different Suspect Source")
            val choose = chooseTriggeredCards(host)
            choose.setSubAbility(subAbility(host, "DB$P AlterAttribute | Defined$P ChosenCard | Attributes$P Suspected"))

            SuspectChoiceClassifier.isSuspectChoice(choose) shouldBe true
        }

        test("rejects triggered ChooseCard scripts whose subability does not suspect the chosen card") {
            val host = forgeCard("Frantic Scapegoat")
            val choose = chooseTriggeredCards(host)
            choose.setSubAbility(subAbility(host, "DB$P AlterAttribute | Defined$P Self | Attributes$P Suspected | Activate$P False"))

            SuspectChoiceClassifier.isSuspectChoice(choose) shouldBe false
        }

        test("rejects non-ChooseCard suspect effects") {
            val host = forgeCard("Frantic Scapegoat")
            val suspectSelf = subAbility(host, "DB$P AlterAttribute | Defined$P Self | Attributes$P Suspected")

            SuspectChoiceClassifier.isSuspectChoice(suspectSelf) shouldBe false
        }
    })

private const val P = "\$"

private fun chooseTriggeredCards(host: Card): SpellAbility =
    AbilityFactory.getAbility("DB$P ChooseCard | DefinedCards$P TriggeredCards", host)

private fun subAbility(
    host: Card,
    script: String,
): AbilitySub = AbilityFactory.getAbility(script, host) as AbilitySub

private fun forgeCard(name: String): Card {
    val db = FModel.getMagicDb().commonCards
    val paperCard =
        db.getCard(name)
            ?: run {
                forge.StaticData.instance().attemptToLoadCard(name)
                db.getCard(name)
            }
            ?: error("Card not found in Forge DB: $name")
    return Card.fromPaperCard(paperCard, null)
}
