package org.grakovne.snake.agent.strategy

import org.grakovne.snake.agent.core.Direction
import org.grakovne.snake.agent.core.GameView
import kotlin.random.Random

/**
 * A bot. May be stateful, so every game gets its own instance (see the factory
 * parameters of Arena/GameRunner call sites). For reproducible runs give the
 * instance a seeded [Random].
 */
fun interface Strategy {
    fun nextMove(game: GameView): Direction
}

object Strategies {
    val names = listOf("greedy", "random", "safe", "safetime", "hug", "band", "sweep")

    fun create(name: String, random: Random = Random.Default): Strategy = when (name) {
        "greedy" -> GreedyStrategy()
        "random" -> RandomStrategy(random)
        "safe" -> SafeGreedyStrategy(timeAware = false)
        "safetime" -> SafeGreedyStrategy(timeAware = true)
        "hug" -> SafeGreedyStrategy(timeAware = false, hugging = true)
        "band" -> SafeGreedyStrategy(timeAware = false, hugging = true, guardHoles = true, random = random)
        // Tournament champion (tune rounds 1-5): no midgame stall commitment, no
        // food-perimeter avoidance, no midgame dead-cell guard, no first-line timed
        // candidate; endgame digestibility guard and sweeps on.
        "sweep" -> SafeGreedyStrategy(
            timeAware = false,
            guardHoles = true,
            random = random,
            knobs = SafeGreedyKnobs(
                stallCommitMidgame = false,
                avoidAroundFood = false,
                guardDeadCells = false,
                timedCandidate = false,
            ),
        )
        else -> error("unknown strategy '$name', available: $names")
    }
}
