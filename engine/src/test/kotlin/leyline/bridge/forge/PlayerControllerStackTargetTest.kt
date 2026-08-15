package leyline.bridge.forge

import forge.game.card.CardFactory
import forge.game.card.CardView
import forge.game.spellability.SpellAbilityView
import forge.game.spellability.StackItemView
import forge.game.trigger.WrappedAbility
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import leyline.game.mapping.FrameIdResolver
import leyline.testkit.BoardTest

/** Production Forge stack views must resolve to exact stack-object values. */
class PlayerControllerStackTargetTest :
    BoardTest({
        test("duplicate-name spells retain distinct stack identities") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Hand)
                    addCard("Grizzly Bears", human, ZoneType.Hand)
                }
            val cards =
                board.human
                    .getZone(ZoneType.Hand)
                    .cards
                    .toList()
            val entries =
                cards.map { card ->
                    card.firstSpellAbility.also { it.activatingPlayer = board.human }.also(board.game.stack::addAndUnfreeze)
                    board.game.stack.first { it.sourceCard.id == card.id }
                }
            val controller = board.bridge.humanController.shouldNotBeNull()
            val candidates =
                entries.mapIndexed { index, entry ->
                    controller.stackTargetCandidate(index, StackItemView(entry)).shouldNotBeNull()
                }

            assertSoftly {
                cards.map { it.name } shouldContainExactly listOf("Grizzly Bears", "Grizzly Bears")
                candidates.map { it.optionIndex } shouldContainExactly listOf(0, 1)
                candidates.map { it.stackInstanceId }.distinct().size shouldBe 2
                candidates.map { it.sourceForgeCardId.value } shouldContainExactly cards.map { it.id }
                candidates.map { it.forgeAbilityId }.distinct().size shouldBe 2
                candidates.forEach {
                    it.isSpell shouldBe true
                    it.isAbility shouldBe false
                    it.isTrigger shouldBe false
                }
            }
        }

        test("copied spells retain distinct stack source identities") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Hand)
                }
            val source =
                board.human
                    .getZone(ZoneType.Hand)
                    .cards
                    .single()
            val original = source.firstSpellAbility.also { it.activatingPlayer = board.human }
            board.game.stack.addAndUnfreeze(original)
            val copy = CardFactory.copySpellAbilityAndPossiblyHost(original, original, board.human)
            board.game.stack.addAndUnfreeze(copy)
            val entries = board.game.stack.filter { it.isSpell }
            val candidates =
                entries.mapIndexed { index, entry ->
                    board.bridge.humanController
                        .shouldNotBeNull()
                        .stackTargetCandidate(index, StackItemView(entry))
                        .shouldNotBeNull()
                }

            assertSoftly {
                candidates.map { it.stackInstanceId }.distinct().size shouldBe 2
                candidates.map { it.sourceForgeCardId.value }.distinct().size shouldBe 2
                candidates.map { it.sourceForgeCardId.value } shouldContainExactly entries.map { it.sourceCard.id }
            }
        }

        test("activated stack ability uses its exact synthetic ability identity") {
            val board =
                startWithBoard { _, human, ai ->
                    addCard("Goblin Fireslinger", human, ZoneType.Battlefield)
                    addCard("Grizzly Bears", ai, ZoneType.Battlefield)
                }
            val source =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
            val allAbilities = source.getAllSpellAbilities().toList()
            val ability =
                allAbilities.firstOrNull { it.isAbility && !it.isManaAbility }
                    ?: error(
                        "no nonmana ability: ${allAbilities.map {
                            it.javaClass.simpleName + ":spell=" + it.isSpell + ":ability=" + it.isAbility + ":mana=" + it.isManaAbility
                        }}",
                    )
            ability.activatingPlayer = board.human
            ability.targetRestrictions = null
            board.game.stack.addAndUnfreeze(ability)
            val entry =
                board.game.stack.firstOrNull { it.sourceCard.id == source.id && it.isAbility }
                    ?: error(
                        "ability not on stack size=${board.game.stack.size()}: " +
                            "spell=${ability.isSpell} ability=${ability.isAbility} " +
                            "activated=${ability.isActivatedAbility} mana=${ability.isManaAbility} " +
                            "targets=${ability.targets.size} restrictions=${ability.targetRestrictions}",
                    )
            val candidate =
                board.bridge.humanController
                    .shouldNotBeNull()
                    .stackTargetCandidate(0, StackItemView(entry))
                    .shouldNotBeNull()
            val viewCandidate =
                board.bridge.humanController
                    .shouldNotBeNull()
                    .stackTargetCandidate(1, SpellAbilityView.get(entry.spellAbility))
                    .shouldNotBeNull()

            assertSoftly {
                candidate.sourceForgeCardId.value shouldBe source.id
                candidate.stackInstanceId shouldBe entry.id
                candidate.forgeAbilityId shouldBe entry.spellAbility.id
                candidate.isSpell shouldBe false
                candidate.isAbility shouldBe true
                candidate.isTrigger shouldBe false
                FrameIdResolver.triggerStackAbilityForgeId(candidate.forgeAbilityId) shouldNotBe candidate.sourceForgeCardId.value
                viewCandidate.stackInstanceId shouldBe entry.id
                viewCandidate.forgeAbilityId shouldBe entry.spellAbility.id
            }
        }

        test("card view selects the spell when a cast trigger shares its source") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Lunarch Veteran", human, ZoneType.Hand)
                }
            val source =
                board.human
                    .getZone(ZoneType.Hand)
                    .cards
                    .single()
            val spell = source.firstSpellAbility.also { it.activatingPlayer = board.human }
            board.game.stack.addAndUnfreeze(spell)
            val trigger =
                source.triggers.firstOrNull { it.overridingAbility != null }
                    ?: error("expected a trigger on ${source.name}")
            board.game.stack.addAndUnfreeze(WrappedAbility(trigger, trigger.overridingAbility, board.human))
            val spellEntry = board.game.stack.first { it.sourceCard.id == source.id && it.isSpell }
            val candidate =
                board.bridge.humanController
                    .shouldNotBeNull()
                    .stackTargetCandidate(0, CardView.get(source))
                    .shouldNotBeNull()

            assertSoftly {
                candidate.stackInstanceId shouldBe spellEntry.id
                candidate.sourceForgeCardId.value shouldBe source.id
                candidate.isSpell shouldBe true
                candidate.isTrigger shouldBe false
            }
        }

        test("card view rejects ambiguous same-source spells") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Hand)
                }
            val source =
                board.human
                    .getZone(ZoneType.Hand)
                    .cards
                    .single()
            val spell = source.firstSpellAbility.also { it.activatingPlayer = board.human }
            board.game.stack.addAndUnfreeze(spell)
            board.game.stack.addAndUnfreeze(spell)

            shouldThrow<IllegalArgumentException> {
                board.bridge.humanController
                    .shouldNotBeNull()
                    .stackTargetCandidate(0, CardView.get(source))
            }
        }

        test("triggered stack entry retains trigger identity separate from its source card") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Lunarch Veteran", human, ZoneType.Battlefield)
                }
            val source =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
            val trigger =
                source.triggers.firstOrNull { it.overridingAbility != null }
                    ?: error("expected a trigger on ${source.name}")
            val wrapped = WrappedAbility(trigger, trigger.overridingAbility, board.human)
            board.game.stack.addAndUnfreeze(wrapped)
            val entry = board.game.stack.first { it.sourceCard.id == source.id && it.isTrigger }
            val candidate =
                board.bridge.humanController
                    .shouldNotBeNull()
                    .stackTargetCandidate(0, StackItemView(entry))
                    .shouldNotBeNull()

            assertSoftly {
                candidate.sourceForgeCardId.value shouldBe source.id
                candidate.stackInstanceId shouldBe entry.id
                candidate.forgeAbilityId shouldBe entry.spellAbility.id
                candidate.isSpell shouldBe false
                candidate.isAbility shouldBe true
                candidate.isTrigger shouldBe true
                FrameIdResolver.triggerStackAbilityForgeId(candidate.forgeAbilityId) shouldNotBe candidate.sourceForgeCardId.value
            }
        }
    })

private fun <T> T?.shouldNotBeNull(): T = this ?: error("expected non-null")
