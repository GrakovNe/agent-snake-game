package org.grakovne.snake.agent.app

import org.grakovne.snake.agent.core.GameConfig
import org.grakovne.snake.agent.sim.GameRunner
import org.grakovne.snake.agent.strategy.Strategies
import org.grakovne.snake.agent.ui.SnakeFrame
import kotlin.random.Random

/**
 * The "show" run: endless games with the Swing UI.
 *
 * ./gradlew show -Psize=40 -Pdelay=20 -Pstrategy=greedy -Pseed=42
 */
fun main() {
    val size = intProp("size", 40)
    val delay = longProp("delay", 20)
    val strategyName = prop("strategy", "greedy")
    val baseSeed = longProp("seed", System.currentTimeMillis())

    val ui = SnakeFrame(size, size)
    var round = 0L

    while (true) {
        val seed = baseSeed + round
        val result = GameRunner.play(
            config = GameConfig(width = size, height = size, seed = seed),
            strategy = Strategies.create(strategyName, Random(seed)),
        ) { game ->
            ui.render(game)
            Thread.sleep(delay)
        }
        println("round $round: $result")
        round++
        Thread.sleep(1_500)
    }
}
