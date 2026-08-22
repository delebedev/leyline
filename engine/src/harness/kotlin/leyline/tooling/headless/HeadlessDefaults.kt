package leyline.tooling.headless

import leyline.game.bundle.InvariantSelection

/** Default validation policy for semantic match specifications. */
fun defaultHeadlessValidation(validating: Boolean): InvariantSelection =
    if (validating) {
        InvariantSelection.protocolFacts()
    } else {
        InvariantSelection.none("validation disabled")
    }
