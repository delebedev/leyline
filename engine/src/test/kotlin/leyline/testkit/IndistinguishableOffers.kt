package leyline.testkit

import leyline.game.state.GameBridge

/** Fails when any action catalog this bridge built dropped an offer the client could not tell apart from another. */
fun GameBridge.checkNoIndistinguishableOffers() {
    val dropped = abilityIdentities.ledger.ambiguousCatalogs()
    check(dropped.isEmpty()) { "Action catalog dropped indistinguishable offers: $dropped" }
}
