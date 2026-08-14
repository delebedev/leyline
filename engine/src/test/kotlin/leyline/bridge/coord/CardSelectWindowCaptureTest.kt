package leyline.bridge.coord

import forge.game.card.Card
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.types.PromptCandidateKind
import leyline.bridge.types.PromptCandidateRefDto
import leyline.testkit.BoardTest

class CardSelectWindowCaptureTest :
    BoardTest({

        val cards = listOf(Card(10, null), Card(11, null))
        val request =
            PromptRequest(
                promptType = "choose_cards",
                message = "Choose a legendary permanent to keep",
                options = listOf("First", "Second"),
                min = 1,
                max = 1,
                defaultIndex = 0,
                candidateRefs =
                    cards.mapIndexed { index, card ->
                        PromptCandidateRefDto(index, PromptCandidateKind.Card, card.id, "Battlefield")
                    },
                route = PromptRouteResolver.resolve(PromptSemantic.SelectNLegendRule),
            )

        test("Legend Rule accepts only its exact producer shape") {
            assertSoftly {
                shouldNotThrowAny { CardSelectWindowCapture.initial(request, cards) }
                shouldThrow<IllegalStateException> {
                    CardSelectWindowCapture.initial(
                        request.copy(options = listOf("Only"), candidateRefs = request.candidateRefs.take(1)),
                        cards.take(1),
                    )
                }
                shouldThrow<IllegalStateException> { CardSelectWindowCapture.initial(request.copy(min = 0), cards) }
                shouldThrow<IllegalStateException> { CardSelectWindowCapture.initial(request.copy(defaultIndex = 1), cards) }
                shouldThrow<IllegalStateException> { CardSelectWindowCapture.initial(request.copy(sourceEntityId = 10), cards) }
            }
        }

        test("Library putback requires a source and exact hand cards") {
            val board =
                startPuzzleAtMain1(
                    """
                    [metadata]
                    Name:library putback capture
                    Goal:Win
                    Turns:1

                    [state]
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=20
                    humanhand=Mountain;Forest
                    humanbattlefield=Island
                    ailibrary=Forest
                    """.trimIndent(),
                )
            val hand =
                board.human
                    .getZone(ZoneType.Hand)
                    .cards
                    .toList()
            val source =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
            val libraryPutback =
                PromptRequest(
                    promptType = "choose_cards",
                    message = "Choose two cards to put back",
                    options = hand.map { it.name },
                    min = 2,
                    max = 2,
                    defaultIndex = 0,
                    candidateRefs =
                        hand.mapIndexed { index, card ->
                            PromptCandidateRefDto(index, PromptCandidateKind.Card, card.id, ZoneType.Hand.name)
                        },
                    route = PromptRouteResolver.resolve(PromptSemantic.SelectNLibraryPutback),
                    sourceEntityId = source.id,
                )

            assertSoftly {
                shouldNotThrowAny { CardSelectWindowCapture.initial(libraryPutback, hand) }
                shouldThrow<IllegalStateException> {
                    CardSelectWindowCapture.initial(libraryPutback.copy(sourceEntityId = null), hand)
                }
                shouldThrow<IllegalStateException> {
                    CardSelectWindowCapture.initial(
                        libraryPutback.copy(
                            options = listOf(source.name),
                            min = 1,
                            max = 1,
                            candidateRefs =
                                listOf(
                                    PromptCandidateRefDto(
                                        0,
                                        PromptCandidateKind.Card,
                                        source.id,
                                        ZoneType.Battlefield.name,
                                    ),
                                ),
                        ),
                        listOf(source),
                    )
                }
            }
        }

        test("Manifest Dread requires exact library cards, source, cardinality, and default") {
            val board =
                startPuzzleAtMain1(
                    """
                    [metadata]
                    Name:manifest dread capture
                    Goal:Win
                    Turns:1

                    [state]
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=20
                    humanlibrary=Mountain;Forest
                    humanbattlefield=Island
                    ailibrary=Forest
                    """.trimIndent(),
                )
            val library =
                board.human
                    .getZone(ZoneType.Library)
                    .cards
                    .toList()
            val source =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
            val manifest =
                PromptRequest(
                    promptType = "choose_cards",
                    message = "Choose a card to manifest",
                    options = library.map { it.name },
                    min = 1,
                    max = 1,
                    defaultIndex = 0,
                    candidateRefs =
                        library.mapIndexed { index, card ->
                            PromptCandidateRefDto(index, PromptCandidateKind.Card, card.id, ZoneType.Library.name)
                        },
                    route = PromptRouteResolver.resolve(PromptSemantic.ManifestDread),
                    sourceEntityId = source.id,
                )

            assertSoftly {
                shouldNotThrowAny { CardSelectWindowCapture.initial(manifest, library) }
                shouldThrow<IllegalStateException> { CardSelectWindowCapture.initial(manifest.copy(sourceEntityId = null), library) }
                shouldThrow<IllegalStateException> { CardSelectWindowCapture.initial(manifest.copy(min = 0), library) }
                shouldThrow<IllegalStateException> { CardSelectWindowCapture.initial(manifest.copy(max = 2), library) }
                shouldThrow<IllegalStateException> { CardSelectWindowCapture.initial(manifest.copy(defaultIndex = 1), library) }
                shouldThrow<IllegalStateException> {
                    CardSelectWindowCapture.initial(
                        manifest.copy(
                            options = listOf(source.name),
                            candidateRefs =
                                listOf(
                                    PromptCandidateRefDto(
                                        0,
                                        PromptCandidateKind.Card,
                                        source.id,
                                        ZoneType.Battlefield.name,
                                    ),
                                ),
                        ),
                        listOf(source),
                    )
                }
            }
        }
    })
