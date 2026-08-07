package leyline.native.frontdoor

import leyline.domain.repo.InMemoryDeckRepository
import leyline.domain.repo.InMemoryPlayerRepository

class InMemoryPlayerDeckRepository(
    decks: InMemoryDeckRepository = InMemoryDeckRepository(),
    players: InMemoryPlayerRepository = InMemoryPlayerRepository(),
) : leyline.domain.repo.DeckRepository by decks,
    leyline.domain.repo.PlayerRepository by players
