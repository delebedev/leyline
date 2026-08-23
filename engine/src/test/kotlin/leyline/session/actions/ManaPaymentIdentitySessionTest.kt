package leyline.session.actions

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import leyline.game.mapping.ZoneIds
import leyline.testkit.SessionTest
import leyline.testkit.after
import leyline.testkit.annotationsOfType
import leyline.testkit.detailInt
import wotc.mtgo.gre.external.messaging.Messages.ActionType
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

class ManaPaymentIdentitySessionTest :
    SessionTest({
        session(
            "cast payment retains each producing mana ability identity",
            puzzle = """
                ActivePlayer=Human
                ActivePhase=Main1
                HumanLife=20
                AILife=20

                humanhand=Grizzly Bears
                humanbattlefield=Mountain;Llanowar Elves
                humanlibrary=Forest
                ailibrary=Mountain
                """,
        ) {
            human.battlefield.card("Llanowar Elves").setSickness(false)

            activateMana("Mountain").shouldBeTrue()
            activateMana("Llanowar Elves").shouldBeTrue()
            val messages = after { castSpellByName("Grizzly Bears").shouldBeTrue() }.messages
            val manaActions =
                messages
                    .annotationsOfType(AnnotationType.UserActionTaken)
                    .filter { it.detailInt("actionType") == ActionType.ActivateMana.number }
            val createdByAbility =
                messages
                    .annotationsOfType(AnnotationType.AbilityInstanceCreated)
                    .associateBy { it.affectedIdsList.single() }
            val tappedByAbility =
                messages
                    .annotationsOfType(AnnotationType.TappedUntappedPermanent)
                    .associateBy { it.affectorId }
            val deletedByAbility =
                messages
                    .annotationsOfType(AnnotationType.AbilityInstanceDeleted)
                    .associateBy { it.affectedIdsList.single() }

            manaActions.map { it.detailInt("abilityGrpId") }.toSet() shouldBe setOf(1004, 1005)
            manaActions.forEach { action ->
                val abilityIid = action.affectedIdsList.single()
                val created = createdByAbility.getValue(abilityIid)
                val tapped = tappedByAbility.getValue(abilityIid)
                val deleted = deletedByAbility.getValue(abilityIid)

                assertSoftly {
                    created.detailInt("source_zone") shouldBe ZoneIds.BATTLEFIELD
                    tapped.affectedIdsList shouldBe listOf(created.affectorId)
                    deleted.affectorId shouldBe created.affectorId
                }
            }
        }
    })
