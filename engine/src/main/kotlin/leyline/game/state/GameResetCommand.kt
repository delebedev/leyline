package leyline.game.state

/** Engine-local command used to replace a running game without exposing its implementation type. */
fun interface GameResetCommand {
    fun reset(bridge: GameBridge): List<Int>
}
