package org.grakovne.snake.agent.app

import org.grakovne.snake.agent.core.GameConfig
import org.grakovne.snake.agent.sim.Arena
import org.grakovne.snake.agent.strategy.Strategies
import kotlin.random.Random

/**
 * The selection run: evaluates several strategies in parallel on a shared seed set and
 * prints a leaderboard. This is the harness a genetic/ML bot plugs into — candidates
 * become weight vectors instead of strategy names, the rest stays the same.
 *
 * ./gradlew arena -Psize=30 -Pgames=100 -Pseed=42
 */
fun main() {
    val size = intProp("size", 30)
    val games = intProp("games", 100)
    val seed = longProp("seed", 42)
    val parallelism = intProp("parallelism", Runtime.getRuntime().availableProcessors())

    println("arena: field=${size}x$size games-per-candidate=$games seed=$seed candidates=${Strategies.names}")
    println()

    val leaderboard = Arena(parallelism).tournament(
        candidates = Strategies.names,
        config = GameConfig(width = size, height = size, seed = seed),
        gamesPerCandidate = games,
    ) { name, index -> Strategies.create(name, Random(seed + index)) }

    leaderboard.forEachIndexed { place, (name, evaluation) ->
        println("#${place + 1} $name")
        println(evaluation.summary().prependIndent("   "))
        println()
    }
}
