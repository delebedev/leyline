package leyline.behavior.cards

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import leyline.game.mapping.PromptIds
import leyline.testkit.SessionTest
import leyline.testkit.beInGraveyardOf
import leyline.testkit.beInHandOf
import wotc.mtgo.gre.external.messaging.Messages.CardType
import wotc.mtgo.gre.external.messaging.Messages.IdType
import wotc.mtgo.gre.external.messaging.Messages.SelectionListType
import wotc.mtgo.gre.external.messaging.Messages.StaticList

class CardTypeChoiceTest :
    SessionTest({
        for (chosen in listOf(CardType.Creature, CardType.Land_a80b)) {
            session(
                "Winding Way choosing $chosen puts exactly that type in hand and continues priority",
                puzzle =
                    """
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=20
                    humanbattlefield=Forest;Forest
                    humanhand=Winding Way;Forest
                    humanlibrary=Grizzly Bears;Mountain;Walking Corpse;Island;Forest;Forest;Forest
                    ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
                    """.trimIndent(),
            ) {
                val req = castSpellUntilSelectNReq("Winding Way")
                val message = allMessages.last { it.hasSelectNReq() }
                assertSoftly {
                    req.listType shouldBe SelectionListType.StaticSubset
                    req.staticList shouldBe StaticList.CardTypes
                    req.idsList shouldBe listOf(CardType.Creature.number, CardType.Land_a80b.number)
                    req.idType shouldBe IdType.None_ab2c
                    req.unfilteredIdsList shouldBe emptyList()
                    req.minSel shouldBe 1
                    req.maxSel shouldBe 1
                    message.prompt.promptId shouldBe PromptIds.CHOOSE_TYPE
                }
                respondToSelectN(listOf(chosen.number))
                passUntilResolved()
                val inHand = if (chosen == CardType.Creature) listOf("Grizzly Bears", "Walking Corpse") else listOf("Mountain", "Island")
                val inGraveyard =
                    if (chosen ==
                        CardType.Creature
                    ) {
                        listOf("Mountain", "Island")
                    } else {
                        listOf("Grizzly Bears", "Walking Corpse")
                    }
                assertSoftly {
                    inHand.forEach { it should beInHandOf(human) }
                    inGraveyard.forEach { it should beInGraveyardOf(human) }
                    "Winding Way" should beInGraveyardOf(human)
                    game().stackZone.size() shouldBe 0
                    bridge.hasPendingNonActionInteraction() shouldBe false
                }
                playLand("Forest") shouldBe true
            }
        }
    })
