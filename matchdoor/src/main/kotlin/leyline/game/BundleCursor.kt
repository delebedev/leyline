package leyline.game

import leyline.game.snapshot.GsmSnapshot

/**
 * Bundle-sequence cursor: the snapshot that was last sent to the client.
 * Serves as the `prev` baseline for the next `StateMapper.buildDiff` call
 * in the bundle loop.
 *
 * Hosted on [GameBridge.bundleCursor] today; ownership lifts to the session
 * layer once the design questions around rejoin/restore and the
 * targeting-flow cursor-invalidate are resolved. Introducing the type now
 * keeps that lift mechanical.
 */
class BundleCursor {
    var lastSent: GsmSnapshot? = null
}
