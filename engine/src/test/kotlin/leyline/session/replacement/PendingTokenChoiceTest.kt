package leyline.session.replacement

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import leyline.bridge.types.ForgeCardId
import leyline.bridge.types.StaticChoiceIds
import leyline.testkit.SessionTest
import wotc.mtgo.gre.external.messaging.Messages.StaticList

class PendingTokenChoiceTest :
    SessionTest({
        session(
            "copied token chooses its own creature type before entering",
            puzzleFile = "data/puzzles/roaming-throne-copy.pzl",
        ) {
            val req =
                castSpellUntilSelectNReq("Molten Duplication") {
                    selectTargets(listOf(instanceIdOf("Roaming Throne", zone = ZoneType.Battlefield)))
                    passUntil(maxPasses = 8) { allMessages.any { it.hasSelectNReq() } }.shouldBeTrue()
                }
            req.staticList shouldBe StaticList.SubTypes
            respondToSelectN(listOf(StaticChoiceIds.subtypeIdFor("Goblin")!!))
            passUntilResolved()
            val thrones = human.getCardsIn(ZoneType.Battlefield).filter { it.name == "Roaming Throne" }
            val token = thrones.single { it.isToken }
            assertSoftly {
                thrones.map { it.chosenType } shouldContainExactlyInAnyOrder listOf("Elf", "Goblin")
                token.type.hasSubtype("Goblin").shouldBeTrue()
                bridge
                    .projectionStateSnapshot()
                    .identities.forgeIdToInstanceId
                    .getValue(ForgeCardId(token.id))
                    .value shouldBe req.sourceId
                hasPendingAction().shouldBeTrue()
            }
        }

        session(
            "original permanent keeps its static creature type choice",
            fullControl = true,
            puzzle =
                """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20
                humanhand=Roaming Throne
                humanbattlefield=Mountain;Mountain;Mountain;Mountain
                humanlibrary=Mountain;Mountain;Mountain
                ailibrary=Forest;Forest;Forest
                """.trimIndent(),
        ) {
            castSpellUntilSelectNReq("Roaming Throne").staticList shouldBe StaticList.SubTypes
            respondToSelectN(listOf(StaticChoiceIds.subtypeIdFor("Goblin")!!))
            passUntilResolved()
            human.battlefield.card("Roaming Throne").chosenType shouldBe "Goblin"
            hasPendingAction().shouldBeTrue()
        }
    })
