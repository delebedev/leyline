package leyline.bridge.interaction

import forge.game.ability.ApiType
import forge.game.card.Card
import forge.game.zone.ZoneType
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import leyline.testkit.BoardTest

class GroupedSearchClassifierTest :
    BoardTest({
        test("partitions the grounded union in declared quality order") {
            val fixture = groupedSearchFixture()

            GroupedSearchClassifier.classify(fixture.ability, listOf(fixture.flash, fixture.instant)) shouldContainExactly
                listOf(listOf(1), listOf(0))
        }

        test("ordinary and unsupported searches remain flat") {
            val fixture = groupedSearchFixture()

            GroupedSearchClassifier.classify(null, listOf(fixture.instant)).shouldBeNull()
        }

        test("refuses overlap and candidates outside the grounded union") {
            val fixture = groupedSearchFixture()
            fixture.instant.addIntrinsicKeyword("Flash")
            shouldThrow<IllegalStateException> {
                GroupedSearchClassifier.classify(fixture.ability, listOf(fixture.instant, fixture.flash))
            }
            shouldThrow<IllegalStateException> {
                GroupedSearchClassifier.classify(fixture.ability, listOf(fixture.instant, fixture.other))
            }
        }
    })

private fun BoardTest.groupedSearchFixture(): GroupedSearchFixture {
    lateinit var teachings: Card
    lateinit var instant: Card
    lateinit var flash: Card
    lateinit var other: Card
    startWithBoard { _, human, _ ->
        teachings = addCard("Mystical Teachings", human, ZoneType.Hand)
        instant = addCard("Brainstorm", human, ZoneType.Library)
        flash = addCard("Nightpack Ambusher", human, ZoneType.Library)
        other = addCard("Grizzly Bears", human, ZoneType.Library)
    }
    return GroupedSearchFixture(
        ability = teachings.spellAbilities.single { it.api == ApiType.ChangeZone },
        instant = instant,
        flash = flash,
        other = other,
    )
}

private data class GroupedSearchFixture(
    val ability: forge.game.spellability.SpellAbility,
    val instant: Card,
    val flash: Card,
    val other: Card,
)
