package leyline.behavior.annotations.copiedobject

import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import leyline.bridge.bootstrap.GameBootstrap
import leyline.game.codes.DetailKeys
import leyline.testkit.*
import leyline.tooling.headless.HeadlessCard
import leyline.tooling.headless.HeadlessMatch
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

/** Copy-token identity is checked through immutable cards and GRE observations. */
class CopyTokenIntegrationTest :
    SessionTest({
        beforeSpec {
            GameBootstrap.initializeCardDatabase(quiet = true)
            TestCardRegistry.ensureRegistered()
            listOf("Electroduplicate", "Grizzly Bears", "Mountain", "Quick Study", "Homunculus Horde", "Island").forEach {
                TestCardRegistry.ensureCardRegistered(it)
            }
        }
        val puzzle =
            """
            [metadata]
            Name:Copy Token Test
            Goal:Create a copy
            Turns:5
            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanhand=Electroduplicate
            humanbattlefield=Grizzly Bears;Mountain;Mountain;Mountain
            humanlibrary=Mountain;Mountain;Mountain;Mountain;Mountain
            aibattlefield=Mountain
            ailibrary=Mountain;Mountain;Mountain;Mountain;Mountain
            """.trimIndent()

        fun HeadlessMatch.copyToken(): HeadlessCard {
            val target = human.battlefield.iid("Grizzly Bears")
            castSpellByName("Electroduplicate").shouldBeTrue()
            selectTargets(listOf(target))
            passUntil(maxPasses = 20) { human.battlefield.cards.any { it.isToken } }
            return human.battlefield.cards.first { it.isToken }
        }

        session("copy token carries source identity and card shape", puzzle = puzzle) {
            val token = copyToken()
            assertSoftly {
                token.isCopy.shouldBeTrue()
                token.objectSourceGrpId shouldBe cardGrpId("Grizzly Bears")
                token.grpId shouldBe cardGrpId("Grizzly Bears")
                token.power shouldBe 2
                token.toughness shouldBe 2
                token.cardTypes shouldContain "Creature"
            }
        }

        session("copy token fields are present in the projected GSM", puzzle = puzzle) {
            val token = copyToken()
            val objectInfo = observe().client.objects[token.id].shouldNotBeNull()
            assertSoftly {
                objectInfo.isCopy shouldBe true
                objectInfo.grpId shouldBe cardGrpId("Grizzly Bears")
                objectInfo.objectSourceGrpId shouldBe cardGrpId("Grizzly Bears")
                objectInfo.cardTypesList.shouldNotBeEmpty()
                objectInfo.power.value shouldBe 2
                objectInfo.toughness.value shouldBe 2
            }
        }

        session("copy token identity survives the next diff observation", puzzle = puzzle) {
            val token = copyToken()
            val checkpoint = checkpoint()
            passPriority()
            val diffObjects =
                messagesSince(checkpoint)
                    .filter { it.hasGameStateMessage() }
                    .flatMap { it.gameStateMessage.gameObjectsList }
                    .filter { it.instanceId == token.id }
            diffObjects.forEach { objectInfo ->
                assertSoftly {
                    objectInfo.isCopy shouldBe true
                    objectInfo.grpId shouldBe cardGrpId("Grizzly Bears")
                    objectInfo.cardTypesList.shouldNotBeEmpty()
                }
            }
            cardByIid(token.id)?.isCopy shouldBe true
        }

        session("copy token retains temporary-permanent and cast annotations", puzzle = puzzle) {
            val token = copyToken()
            val annotations =
                allMessages.flatMap {
                    if (it.hasGameStateMessage()) {
                        it.gameStateMessage.annotationsList + it.gameStateMessage.persistentAnnotationsList
                    } else {
                        emptyList()
                    }
                }
            assertSoftly {
                val temporary =
                    annotations.filter {
                        AnnotationType.TemporaryPermanent in it.typeList && it.affectorId == token.id
                    }
                temporary shouldHaveSize 1
                temporary.single().affectedIdsList shouldContain token.id
                temporary.single().detailInt(DetailKeys.ABILITY_GRP_ID_UPPER) shouldBe 192424
                annotations.filter { AnnotationType.UserActionTaken in it.typeList }.shouldNotBeEmpty()
                annotations.flatMap { it.detailsList }.filter { it.key == DetailKeys.GRPID }.shouldNotBeEmpty()
                annotations.count { AnnotationType.TemporaryPermanent in it.typeList } shouldBe 1
            }
        }

        val homunculusPuzzle =
            """
            [metadata]
            Name:Homunculus Horde Copy
            Goal:Win
            Turns:5
            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=20
            humanhand=Quick Study
            humanbattlefield=Homunculus Horde;Island;Island;Island
            humanlibrary=Island;Island;Island;Island;Island;Island;Island
            aibattlefield=Island
            ailibrary=Island;Island;Island;Island
            """.trimIndent()

        fun HeadlessMatch.copyHorde(): HeadlessCard {
            castSpellByName("Quick Study").shouldBeTrue()
            repeat(15) { if (!human.battlefield.cards.any { it.isToken }) passPriority() }
            return human.battlefield.cards.first { it.isToken }
        }

        session("Homunculus Horde copy gets source grpId and isCopy", puzzle = homunculusPuzzle) {
            val copy = copyHorde()
            assertSoftly {
                copy.isCopy.shouldBeTrue()
                copy.isToken.shouldBeTrue()
                copy.grpId shouldBe cardGrpId("Homunculus Horde")
                copy.objectSourceGrpId shouldBe cardGrpId("Homunculus Horde")
                copy.power shouldBe 2
                copy.toughness shouldBe 2
                copy.cardTypes shouldContain "Creature"
            }
        }

        session("Homunculus Horde copy has no TemporaryPermanent annotation", puzzle = homunculusPuzzle) {
            val copy = copyHorde()
            val temporary =
                allMessages
                    .flatMap { message ->
                        if (message.hasGameStateMessage()) message.gameStateMessage.persistentAnnotationsList else emptyList()
                    }.filter { annotation ->
                        AnnotationType.TemporaryPermanent in annotation.typeList && annotation.affectorId == copy.id
                    }
            temporary.shouldBeEmpty()
        }
    })
