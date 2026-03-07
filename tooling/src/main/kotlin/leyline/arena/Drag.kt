package leyline.arena

object Drag {

    fun run(args: List<String>) {
        if (args.size < 2) {
            System.err.println("Usage: arena drag <from_x>,<from_y> <to_x>,<to_y>")
            throw SystemExitException(1)
        }

        val from = parseCoord(args[0]) ?: run {
            System.err.println("Invalid from coord: ${args[0]} (expected x,y)")
            throw SystemExitException(1)
        }
        val to = parseCoord(args[1]) ?: run {
            System.err.println("Invalid to coord: ${args[1]} (expected x,y)")
            throw SystemExitException(1)
        }

        val bounds = Shell.mtgaWindowBounds()
        if (bounds == null) {
            System.err.println("MTGA window not found")
            throw SystemExitException(1)
        }

        val sx1 = bounds.x + from.first
        val sy1 = bounds.y + from.second
        val sx2 = bounds.x + to.first
        val sy2 = bounds.y + to.second

        val r = Shell.drag(sx1, sy1, sx2, sy2)
        if (!r.ok) {
            System.err.println("drag failed: ${r.stderr}")
            throw SystemExitException(1)
        }
        println("dragged (${from.first},${from.second}) → (${to.first},${to.second})")
    }

    private fun parseCoord(s: String): Pair<Int, Int>? {
        val m = Regex("""^(\d+),(\d+)$""").matchEntire(s) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }
}
