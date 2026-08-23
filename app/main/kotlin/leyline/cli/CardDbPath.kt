package leyline.cli

import leyline.game.data.ClientCardDatabase

/**
 * Print the resolved, validated client card database path.
 *
 * Operator tooling (the just lookup recipes) uses this to obtain one
 * validated path for thin SQL instead of re-implementing discovery and
 * validation per recipe.
 */
fun main() {
    println(ClientCardDatabase.resolveValidatedPath().absolutePath)
}
