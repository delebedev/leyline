package leyline.bridge.coord

import forge.game.card.Card
import forge.game.zone.ZoneType
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import leyline.bridge.handoff.CardSelectOriginZone
import leyline.bridge.handoff.PromptRequest
import leyline.bridge.handoff.PromptRouteResolver
import leyline.bridge.handoff.PromptSemantic
import leyline.bridge.handoff.ResolutionAbilityShape
import leyline.bridge.handoff.ResolutionRouteInput
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

        test("projected card choices freeze only their exact source zones and cardinalities") {
            val board =
                startPuzzleAtMain1(
                    """
                    [metadata]
                    Name:projected card choice capture
                    Goal:Win
                    Turns:1

                    [state]
                    ActivePlayer=Human
                    ActivePhase=Main1
                    HumanLife=20
                    AILife=20
                    humanhand=Mountain
                    humanlibrary=Forest;Grizzly Bears
                    humansideboard=Environmental Sciences
                    humanbattlefield=Island
                    ailibrary=Forest
                    """.trimIndent(),
                )
            val source =
                board.human
                    .getZone(ZoneType.Battlefield)
                    .cards
                    .single()
            val library =
                board.human
                    .getZone(ZoneType.Library)
                    .cards
                    .toList()
            val hand =
                board.human
                    .getZone(ZoneType.Hand)
                    .cards
                    .single()
            val sideboard =
                board.human
                    .getZone(ZoneType.Sideboard)
                    .cards
                    .single()

            fun request(
                semantic: PromptSemantic,
                candidates: List<Card>,
                min: Int,
            ): PromptRequest =
                PromptRequest(
                    promptType = "choose_cards",
                    message = "Choose a card",
                    options = candidates.map { it.name },
                    min = min,
                    max = 1,
                    defaultIndex = 0,
                    candidateRefs =
                        candidates.mapIndexed { index, card ->
                            PromptCandidateRefDto(index, PromptCandidateKind.Card, card.id, card.zone.zoneType.name)
                        },
                    route =
                        PromptRouteResolver.resolve(
                            semantic,
                            resolutionInput =
                                candidates
                                    .takeIf { semantic == PromptSemantic.SelectNResolution }
                                    ?.let {
                                        ResolutionRouteInput(
                                            optionCount = it.size,
                                            candidateCount = it.size,
                                            candidateKinds = setOf(PromptCandidateKind.Card),
                                            candidateZones = setOf(ZoneType.Library.name),
                                            abilityShape = ResolutionAbilityShape.Dig,
                                        )
                                    },
                        ),
                    sourceEntityId = source.id,
                )

            val resolution = request(PromptSemantic.SelectNResolution, library, min = 1)
            val learnHandles = listOf(sideboard, hand)
            val learn = request(PromptSemantic.LearnLesson, learnHandles, min = 0)

            assertSoftly {
                CardSelectWindowCapture
                    .initial(resolution, library)
                    .value.candidates
                    .map { it.originZone } shouldContainExactly
                    listOf(CardSelectOriginZone.Library, CardSelectOriginZone.Library)
                shouldNotThrowAny { CardSelectWindowCapture.initial(resolution.copy(min = 0), library) }
                CardSelectWindowCapture
                    .initial(learn, learnHandles)
                    .value.candidates
                    .map { it.originZone } shouldContainExactly
                    listOf(CardSelectOriginZone.Sideboard, CardSelectOriginZone.Hand)
                shouldThrow<IllegalStateException> {
                    CardSelectWindowCapture.initial(resolution.copy(sourceEntityId = null), library)
                }
                shouldThrow<IllegalStateException> {
                    CardSelectWindowCapture.initial(
                        resolution.copy(
                            options = listOf(hand.name),
                            candidateRefs =
                                listOf(PromptCandidateRefDto(0, PromptCandidateKind.Card, hand.id, ZoneType.Hand.name)),
                        ),
                        listOf(hand),
                    )
                }
                shouldThrow<IllegalStateException> {
                    CardSelectWindowCapture.initial(learn.copy(sourceEntityId = null), learnHandles)
                }
                shouldThrow<IllegalStateException> { CardSelectWindowCapture.initial(learn.copy(min = 1), learnHandles) }
                shouldThrow<IllegalStateException> {
                    CardSelectWindowCapture.initial(
                        learn.copy(
                            options = library.map { it.name },
                            candidateRefs = resolution.candidateRefs,
                        ),
                        library,
                    )
                }
            }
        }
    })
