package org.grakovne.snake.agent.app

import org.grakovne.snake.agent.core.GameConfig
import org.grakovne.snake.agent.sim.GameRunner
import org.grakovne.snake.agent.strategy.Strategies
import org.grakovne.snake.agent.ui.SnakeFrame
import java.io.File
import kotlin.random.Random

/**
 * The "show" run: endless games with the UI. Speed is set in engine ticks per second
 * (rendering is decoupled from the engine and always smooth).
 *
 * ./gradlew show -Psize=40 -Ptps=2500 -Pstrategy=neural -Pseed=42
 */
fun main() {
    val size = intProp("size", 40)
    val tps = intProp("tps", 2500)
    val hasNet = File("data/value-net.onnx").exists() ||
        object {}.javaClass.getResource("/value-net.onnx") != null
    val defaultStrategy = if (hasNet) "neural" else "sweep"
    val strategyName = prop("strategy", defaultStrategy)
    val baseSeed = longProp("seed", System.currentTimeMillis())

    val ui = SnakeFrame(size, size)
    val area = size * size
    var round = 0L

    while (true) {
        val seed = baseSeed + round
        ui.newGame(round, strategyName, seed)
        val result = GameRunner.play(
            config = GameConfig(width = size, height = size, seed = seed),
            strategy = Strategies.create(strategyName, Random(seed)),
        ) { game ->
            ui.render(game)
            // Finale slow-motion: over the last 50 cells the tick rate eases down,
            // so the hardest part of the game is watchable.
            val free = area - game.score
            val effectiveTps = if (free > 50) tps else maxOf(250, tps * free / 50)
            val sleepEvery = maxOf(1, effectiveTps / 1000)
            if (effectiveTps < 1_000_000 && game.steps % sleepEvery == 0) Thread.sleep(1)
        }
        println("round $round: $result")
        round++
        Thread.sleep(2_500)
    }
}
