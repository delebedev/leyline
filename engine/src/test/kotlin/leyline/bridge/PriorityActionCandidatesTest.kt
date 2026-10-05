package leyline.bridge

import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import leyline.bridge.handoff.PlayerAction
import leyline.bridge.types.ForgeCardId
import leyline.game.mapping.ActionMapper
import leyline.game.snapshot.SnapshotCapture
import leyline.testkit.BoardTest
import leyline.testkit.ofType
import wotc.mtgo.gre.external.messaging.Messages.ActionType

class PriorityActionCandidatesTest :
    BoardTest({
        test("legal but unaffordable cast stays inactive without holding priority") {
            val board =
                startWithBoard { _, human, _ ->
                    addCard("Grizzly Bears", human, ZoneType.Hand)
                }

            val candidates = PriorityActionCandidates.query(board.game, board.human)
            val projection =
                ActionMapper.buildProjectionFromSnapshot(
                    1,
                    SnapshotCapture.run(board.game, board.bridge, "test", 0),
                    board.bridge,
                    candidates,
                )

            assertSoftly {
                candidates.hasLegalNonManaAction(board.human).shouldBeFalse()
                projection.actions.ofType(ActionType.Cast).shouldHaveSize(0)
                projection.actions.inactiveActionsList
                    .filter { it.actionType == ActionType.Cast }
                    .shouldHaveSize(1)
            }
        }
        for (spell in listOf("Counterspell", "Murder", "Bone Splinters", "Run Away Together")) {
            test("unavailable targets or sacrifice material do not hold priority: $spell") {
                val board =
                    startWithBoard { _, human, ai ->
                        addCard(spell, human, ZoneType.Hand)
                        val land = if (spell in listOf("Counterspell", "Run Away Together")) "Island" else "Swamp"
                        repeat(3) { addCard(land, human, ZoneType.Battlefield) }
                        if (spell in listOf("Bone Splinters", "Run Away Together")) addCard("Grizzly Bears", ai, ZoneType.Battlefield)
                    }
                val candidates = PriorityActionCandidates.query(board.game, board.human)
                val projection =
                    ActionMapper.buildProjectionFromSnapshot(
                        1,
                        SnapshotCapture.run(board.game, board.bridge, "test", 0),
                        board.bridge,
                        candidates,
                    )
                assertSoftly {
                    candidates.hasLegalNonManaAction(board.human).shouldBeFalse()
                    candidates.hasLegalNonManaAction(board.human, isOwnTurn = false).shouldBeFalse()
                    projection.actions.ofType(ActionType.Cast).shouldHaveSize(0)
                }
            }
        }
        for (payable in listOf(false, true)) {
            test("graveyard casting uses the same availability for either turn: $payable") {
                val board =
                    startWithBoard { _, human, _ ->
                        addCard("Think Twice", human, ZoneType.Graveyard)
                        if (payable) repeat(3) { addCard("Island", human, ZoneType.Battlefield) }
                    }
                val candidates = PriorityActionCandidates.query(board.game, board.human)
                val projection =
                    ActionMapper.buildProjectionFromSnapshot(
                        1,
                        SnapshotCapture.run(board.game, board.bridge, "test", 0),
                        board.bridge,
                        candidates,
                    )
                assertSoftly {
                    candidates.hasLegalNonManaAction(board.human) shouldBe payable
                    candidates.hasLegalNonManaAction(board.human, isOwnTurn = false) shouldBe payable
                    projection.actions.ofType(ActionType.Cast).shouldHaveSize(if (payable) 1 else 0)
                }
            }
            test("zero-mana sacrifice activation requires its material: $payable") {
                val board =
                    startWithBoard { _, human, _ ->
                        addCard("Immersturm Predator", human, ZoneType.Battlefield)
                        if (payable) addCard("Grizzly Bears", human, ZoneType.Battlefield)
                    }
                val candidates = PriorityActionCandidates.query(board.game, board.human)
                val projection =
                    ActionMapper.buildProjectionFromSnapshot(
                        1,
                        SnapshotCapture.run(board.game, board.bridge, "test", 0),
                        board.bridge,
                        candidates,
                    )
                assertSoftly {
                    candidates.hasLegalNonManaAction(board.human) shouldBe payable
                    candidates.hasLegalNonManaAction(board.human, isOwnTurn = false) shouldBe payable
                    projection.actions.ofType(ActionType.Activate_add3).shouldHaveSize(if (payable) 1 else 0)
                }
            }
        }
        for (life in listOf(1, 20)) {
            test("library spell permission retains exact life-cost offer: $life") {
                val board =
                    startPuzzleAtMain1(
                        """
                        [metadata]
                        Name:Library spell permission
                        Goal:Win
                        Turns:4
                        Difficulty:Easy
                        Description:Cast the top spell using life.
                        [state]
                        ActivePlayer=Human
                        ActivePhase=Main1
                        HumanLife=$life
                        AILife=20
                        humanbattlefield=Bolas's Citadel
                        humanlibrary=Grizzly Bears;Llanowar Elves;Forest
                        ailibrary=Ornithopter;Mountain
                        """.trimIndent(),
                    )
                val candidates = PriorityActionCandidates.query(board.game, board.human)
                val projection =
                    ActionMapper.buildProjectionFromSnapshot(
                        1,
                        SnapshotCapture.run(board.game, board.bridge, "test", 0),
                        board.bridge,
                        candidates,
                    )
                val top =
                    board.human
                        .getZone(ZoneType.Library)
                        .cards
                        .first()
                val below =
                    board.human
                        .getZone(ZoneType.Library)
                        .cards
                        .get(1)
                val opponentTop =
                    board.ai
                        .getZone(ZoneType.Library)
                        .cards
                        .first()
                assertSoftly {
                    candidates.forCard(below).casts.shouldHaveSize(0)
                    candidates.forCard(opponentTop).casts.shouldHaveSize(0)
                    candidates.hasLegalNonManaAction(board.human) shouldBe (life >= 2)
                    projection.actions.ofType(ActionType.Cast).shouldHaveSize(if (life >= 2) 1 else 0)
                    if (life >= 2) {
                        val command =
                            projection.offers
                                .single { it.action.actionType == ActionType.Cast }
                                .command
                                .shouldBeInstanceOf<PlayerAction.CastSpell>()
                        command.cardId shouldBe ForgeCardId(top.id)
                        command.ability!!.payCosts.toSimpleString() shouldBe "{0}, Pay 2 life"
                    }
                }
            }
        }
        test("library land permission obeys the normal land limit") {
            val board =
                startPuzzleAtMain1(
                    """
                    [metadata]
                    Name:Library land permission
                    Goal:Win
                    Turns:4
                    Difficulty:Easy
                    Description:Play the top land.
                    [state]
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=20
                    humanbattlefield=Bolas's Citadel
                    humanlibrary=Forest;Island
                    ailibrary=Mountain
                    """.trimIndent(),
                )

            fun projection() =
                ActionMapper.buildProjectionFromSnapshot(
                    1,
                    SnapshotCapture.run(board.game, board.bridge, "test", 0),
                    board.bridge,
                    PriorityActionCandidates.query(board.game, board.human),
                )
            projection().actions.ofType(ActionType.Play_add3).shouldHaveSize(1)
            board.human.setLandsPlayedThisTurn(1)
            projection().actions.ofType(ActionType.Play_add3).shouldHaveSize(0)
        }
        test("zero-cost cast is meaningful with no mana sources") {
            val board = startWithBoard { _, human, _ -> addCard("Ornithopter", human, ZoneType.Hand) }
            val candidates = PriorityActionCandidates.query(board.game, board.human)
            val projection =
                ActionMapper.buildProjectionFromSnapshot(
                    1,
                    SnapshotCapture.run(board.game, board.bridge, "test", 0),
                    board.bridge,
                    candidates,
                )
            candidates.hasLegalNonManaAction(board.human).shouldBeTrue()
            projection.actions.ofType(ActionType.Cast).shouldHaveSize(1)
        }
        test("land with graveyard permission uses the ordinary play action") {
            val board =
                startPuzzleAtMain1(
                    """
                    [metadata]
                    Name:Graveyard land play
                    Goal:Play the Specified Permanent
                    Turns:1
                    Difficulty:Tutorial
                    Targets:Island
                    Description:Play a permitted land from the graveyard.

                    [state]
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=20
                    humanbattlefield=Icetill Explorer
                    humangraveyard=Island
                    humanlibrary=Forest
                    ailibrary=Mountain
                    """.trimIndent(),
                )
            val island =
                board.human
                    .getZone(ZoneType.Graveyard)
                    .cards
                    .single()
            val candidates = PriorityActionCandidates.query(board.game, board.human)
            val projection =
                ActionMapper.buildProjectionFromSnapshot(
                    1,
                    SnapshotCapture.run(board.game, board.bridge, "test", 0),
                    board.bridge,
                    candidates,
                )

            assertSoftly {
                candidates.hasLegalNonManaAction(board.human).shouldBeTrue()
                projection.actions.ofType(ActionType.Play_add3).shouldHaveSize(1)
                projection.offers
                    .single { it.action.actionType == ActionType.Play_add3 }
                    .command
                    .shouldBeInstanceOf<PlayerAction.PlayLand>()
                    .cardId shouldBe ForgeCardId(island.id)
            }
        }
        test("graveyard land without permission is not offered") {
            val board = startWithBoard { _, human, _ -> addCard("Island", human, ZoneType.Graveyard) }
            val candidates = PriorityActionCandidates.query(board.game, board.human)
            val projection =
                ActionMapper.buildProjectionFromSnapshot(
                    1,
                    SnapshotCapture.run(board.game, board.bridge, "test", 0),
                    board.bridge,
                    candidates,
                )

            assertSoftly {
                candidates.hasLegalNonManaAction(board.human).shouldBeFalse()
                projection.actions.ofType(ActionType.Play_add3).shouldHaveSize(0)
                projection.actions.inactiveActionsList
                    .filter { it.actionType == ActionType.Play_add3 }
                    .shouldHaveSize(0)
            }
        }
        for (manaAvailable in listOf(false, true)) {
            test("opponent cycling stop requires payable mana: $manaAvailable") {
                val board =
                    startWithBoard { _, human, _ ->
                        addCard("Shark Typhoon", human, ZoneType.Hand)
                        if (manaAvailable) repeat(2) { addCard("Island", human, ZoneType.Battlefield) }
                    }
                val candidates = PriorityActionCandidates.query(board.game, board.human)
                val projection =
                    ActionMapper.buildProjectionFromSnapshot(
                        1,
                        SnapshotCapture.run(board.game, board.bridge, "test", 0),
                        board.bridge,
                        candidates,
                    )
                assertSoftly {
                    if (manaAvailable) {
                        candidates.hasLegalNonManaAction(board.human, isOwnTurn = false).shouldBeTrue()
                        PriorityActionCandidates.hasLegalNonManaAction(board.game, board.human, isOwnTurn = false).shouldBeTrue()
                        projection.actions.ofType(ActionType.Activate_add3).shouldHaveSize(1)
                    } else {
                        candidates.hasLegalNonManaAction(board.human, isOwnTurn = false).shouldBeFalse()
                        PriorityActionCandidates.hasLegalNonManaAction(board.game, board.human, isOwnTurn = false).shouldBeFalse()
                        projection.actions.ofType(ActionType.Activate_add3).shouldHaveSize(0)
                    }
                }
            }
        }
    })
