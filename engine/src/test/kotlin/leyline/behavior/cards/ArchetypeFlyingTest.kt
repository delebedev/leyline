package leyline.behavior.cards

import forge.game.combat.CombatUtil
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.testkit.SessionTest
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

class ArchetypeFlyingTest :
    SessionTest({
        session(
            "opposing printed flying is suppressed for legality and restored after source destruction",
            fullControl = true,
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=40
                AILife=40
                removesummoningsickness=true
                humanbattlefield=Archetype of Imagination;Aminatou, Veil Piercer;Healer's Hawk;Forest;Forest
                humanhand=Naturalize;Ornithopter
                humanlibrary=Plains;Plains;Plains
                aibattlefield=Atraxa, Praetors' Voice;Vorinclex
                ailibrary=Forest;Forest;Forest
            """,
        ) {
            val sourceIid = human.battlefield.iid("Archetype of Imagination")
            val aminatou = human.battlefield.card("Aminatou, Veil Piercer")
            val hawk = human.battlefield.card("Healer's Hawk")
            val atraxa = ai.battlefield.card("Atraxa, Praetors' Voice")
            val atraxaIid = ai.battlefield.iid(atraxa.name)
            val before =
                accumulator.objects
                    .getValue(atraxaIid)
                    .uniqueAbilitiesList
                    .map { it.grpId }
            assertSoftly {
                atraxa.hasKeyword("Flying").shouldBeFalse()
                aminatou.hasKeyword("Flying").shouldBeTrue()
                hawk.hasKeyword("Flying").shouldBeTrue()
                CombatUtil.canBlock(hawk, atraxa).shouldBeFalse()
                CombatUtil.canBlock(aminatou, atraxa).shouldBeFalse()
            }
            println("ARCHETYPE_ACTIVE effectiveAtraxaFlying=${atraxa.hasKeyword("Flying")} projectedAbilities=$before")
            castSpellByName("Naturalize").shouldBeTrue()
            selectTargets(listOf(sourceIid))
            passUntilResolved()
            val after =
                accumulator.objects
                    .getValue(atraxaIid)
                    .uniqueAbilitiesList
                    .map { it.grpId }
            assertSoftly {
                atraxa.hasKeyword("Flying").shouldBeTrue()
                aminatou.hasKeyword("Flying").shouldBeFalse()
                hawk.hasKeyword("Flying").shouldBeTrue()
                CombatUtil.canBlock(hawk, atraxa).shouldBeTrue()
                bridge
                    .projectionStateSnapshot()
                    .persistentAnnotations.activeAnnotations.values
                    .none { AnnotationType.RemoveAbility in it.typeList }
                    .shouldBeTrue()
            }
            println("ARCHETYPE_REMOVED effectiveAtraxaFlying=${atraxa.hasKeyword("Flying")} projectedAbilities=$after")
            before shouldBe after
            castSpellByName("Ornithopter").shouldBeTrue()
            passUntilResolved()
            human.battlefield.card("Ornithopter").name shouldBe "Ornithopter"
        }

        session(
            "opposing Archetypes prevent flying on both sides until one source leaves",
            fullControl = true,
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=40
                AILife=40
                removesummoningsickness=true
                humanbattlefield=Archetype of Imagination;Healer's Hawk;Forest;Forest
                humanhand=Naturalize;Ornithopter
                humanlibrary=Plains;Plains;Plains
                aibattlefield=Atraxa, Praetors' Voice;Archetype of Imagination
                ailibrary=Forest;Forest;Forest
            """,
        ) {
            val ownSource = human.battlefield.card("Archetype of Imagination")
            val opposingSourceIid = ai.battlefield.iid("Archetype of Imagination")
            val opposingSource = ai.battlefield.card("Archetype of Imagination")
            val hawk = human.battlefield.card("Healer's Hawk")
            val atraxa = ai.battlefield.card("Atraxa, Praetors' Voice")
            assertSoftly {
                ownSource.hasKeyword("Flying").shouldBeFalse()
                opposingSource.hasKeyword("Flying").shouldBeFalse()
                hawk.hasKeyword("Flying").shouldBeFalse()
                atraxa.hasKeyword("Flying").shouldBeFalse()
                CombatUtil.canBlock(hawk, atraxa).shouldBeTrue()
            }
            castSpellByName("Naturalize").shouldBeTrue()
            selectTargets(listOf(opposingSourceIid))
            passUntilResolved()
            assertSoftly {
                ownSource.hasKeyword("Flying").shouldBeTrue()
                hawk.hasKeyword("Flying").shouldBeTrue()
                atraxa.hasKeyword("Flying").shouldBeFalse()
                CombatUtil.canBlock(hawk, atraxa).shouldBeFalse()
            }
            castSpellByName("Ornithopter").shouldBeTrue()
            passUntilResolved()
            human.battlefield.card("Ornithopter").name shouldBe "Ornithopter"
        }
    })
