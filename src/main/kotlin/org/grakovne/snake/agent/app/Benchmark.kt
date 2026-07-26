package org.grakovne.snake.agent.app

import org.grakovne.snake.agent.core.GameConfig
import org.grakovne.snake.agent.sim.Arena
import org.grakovne.snake.agent.strategy.Strategies
import kotlin.random.Random

/**
 * Headless benchmark: many games in parallel, aggregate stats and throughput.
 *
 * ./gradlew benchmark -Psize=30 -Pgames=200 -Pseed=42 -Pstrategy=greedy -Pparallelism=8
 */
fun main() {
    val size = intProp("size", 30)
    val games = intProp("games", 200)
    val seed = longProp("seed", 42)
    val strategyName = prop("strategy", "greedy")
    val parallelism = intProp("parallelism", Runtime.getRuntime().availableProcessors())

    println("benchmark: strategy=$strategyName field=${size}x$size games=$games seed=$seed parallelism=$parallelism")

    val startedAt = System.nanoTime()
    val evaluation = Arena(parallelism).evaluate(
        config = GameConfig(width = size, height = size, seed = seed),
        games = games,
    ) { index -> Strategies.create(strategyName, Random(seed + index)) }
    val elapsedSeconds = (System.nanoTime() - startedAt) / 1e9

    println(evaluation.summary())
    println(
        "time: %.2fs (%.1f games/s, %.0f steps/s)".format(
            elapsedSeconds,
            games / elapsedSeconds,
            evaluation.totalSteps / elapsedSeconds,
        )
    )
}
