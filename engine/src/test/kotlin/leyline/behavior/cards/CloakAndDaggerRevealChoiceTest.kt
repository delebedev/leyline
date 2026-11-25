package leyline.behavior.cards

import io.kotest.matchers.booleans.shouldBeTrue
import leyline.testkit.SessionTest

/** Cloak and Dagger, Entwined offers a card from the revealed hand or the targeted creature. */
class CloakAndDaggerRevealChoiceTest :
    SessionTest({
        for (targetCreature in listOf(false, true)) {
            val label = if (targetCreature) "with" else "without"
            session(
                "cloak and dagger resolves $label a targeted creature",
                puzzle =
                    """
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=20
                    humanhand=Cloak and Dagger, Entwined
                    humanbattlefield=Plains;Swamp;Swamp
                    humanlibrary=Plains;Plains;Plains
                    aihand=Divination;Island
                    aibattlefield=Super-Skrull
                    ailibrary=Island;Island;Island
                    """.trimIndent(),
            ) {
                castSpellByName("Cloak and Dagger, Entwined").shouldBeTrue()
                passUntil(maxPasses = 10) { allMessages.lastOrNull()?.hasSelectTargetsReq() == true }.shouldBeTrue()
                selectTargets(listOf(OPPONENT_SEAT))
                passUntil(maxPasses = 5) { allMessages.lastOrNull()?.hasSelectTargetsReq() == true }.shouldBeTrue()
                selectTargets(if (targetCreature) listOf(ai.battlefield.iid("Super-Skrull")) else emptyList())
                passUntil(maxPasses = 10) { allMessages.lastOrNull()?.hasSelectNReq() == true }.shouldBeTrue()
            }
        }
    })
