package leyline.game.generator

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import leyline.BoardTag
import leyline.config.PuzzleDefinition
import leyline.game.data.ForgeCardRepository

class PuzzleValidationTest :
    FunSpec({
        tags(BoardTag)

        val validator by lazy { PuzzleValidation(ForgeCardRepository.open()) }
        val content =
            """
            [metadata]
            Name:One spell
            Goal:Win
            Turns:1
            Difficulty:Easy
            Description:Win this turn.

            [state]
            ActivePlayer=Human
            ActivePhase=Main1
            HumanLife=20
            AILife=3
            humanhand=Lightning Bolt
            humanbattlefield=Mountain
            humanlibrary=Mountain
            ailibrary=Mountain
            """.trimIndent()

        test("valid candidate is applied to a disposable engine game without claiming a solution") {
            val result = validator.validate(PuzzleDefinition("one-spell", content))
            assertSoftly {
                result.status shouldBe PuzzleValidationStatus.Loaded
                result.name shouldBe "One spell"
                result.goal shouldBe "Win"
                result.turns shouldBe 1
                result.inputCardCount shouldBe 4
                result.issues shouldHaveSize 0
            }
            validator.validate(PuzzleDefinition("repeat", content)).status shouldBe PuzzleValidationStatus.Loaded
        }

        test("unknown card is rejected instead of silently disappearing from the position") {
            val result = validator.validate(PuzzleDefinition("unknown", content.replace("Lightning Bolt", "No Such Puzzle Card")))
            result.status shouldBe PuzzleValidationStatus.Invalid
            result.issues.single() shouldContain "No Such Puzzle Card"
        }

        test("unsupported goals and state fields never become a loaded candidate") {
            val goal = validator.validate(PuzzleDefinition("goal", content.replace("Goal:Win", "Goal:Have fun")))
            goal.status shouldBe PuzzleValidationStatus.Unsupported
            goal.issues.single() shouldContain "Goal"
            val state = validator.validate(PuzzleDefinition("state", "$content\nhumansurprise=7"))
            state.status shouldBe PuzzleValidationStatus.Unsupported
            state.issues.single() shouldContain "humansurprise"
            val flag =
                validator.validate(
                    PuzzleDefinition("flag", content.replace("humanhand=Lightning Bolt", "humanhand=Lightning Bolt|Mystery")),
                )
            flag.status shouldBe PuzzleValidationStatus.Unsupported
            flag.issues.single() shouldContain "Mystery"
        }

        test("malformed metadata and ambiguous player positions fail before engine application") {
            for (invalid in listOf(content.replace("Turns:1", "Turns:zero"), content.replace("HumanLife=20", "HumanLife=0"))) {
                validator.validate(PuzzleDefinition("invalid", invalid)).status shouldBe PuzzleValidationStatus.Invalid
            }
            validator.validate(PuzzleDefinition("phase", content.replace("ActivePhase=Main1", "ActivePhase=Combat"))).status shouldBe
                PuzzleValidationStatus.Unsupported
            validator.validate(PuzzleDefinition("duplicate", "$content\np0life=12")).status shouldBe PuzzleValidationStatus.Invalid
            val onePlayer = content.lineSequence().filterNot { it.lowercase().startsWith("ai") }.joinToString("\n")
            validator.validate(PuzzleDefinition("one-player", onePlayer)).status shouldBe PuzzleValidationStatus.Invalid
        }
    })
