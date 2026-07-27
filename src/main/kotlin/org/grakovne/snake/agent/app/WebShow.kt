package org.grakovne.snake.agent.app

import org.grakovne.snake.agent.core.GameConfig
import org.grakovne.snake.agent.sim.GameRunner
import org.grakovne.snake.agent.strategy.Strategies
import org.grakovne.snake.agent.web.WebShowServer
import java.io.File
import kotlin.random.Random

/**
 * Broadcast show: the backend plays endless games, browsers watch the same stream.
 *
 * ./gradlew webshow -Pport=8080 -Psize=40 -Ptps=2500 -Pstrategy=neural
 */
fun main() {
    val size = intProp("size", 40)
    val tps = intProp("tps", 2500)
    val port = intProp("port", 8080)
    val hasNet = File("data/value-net.onnx").exists() ||
        object {}.javaClass.getResource("/value-net.onnx") != null
    val defaultStrategy = if (hasNet) "neural" else "sweep"
    val strategyName = prop("strategy", defaultStrategy)
    val baseSeed = longProp("seed", System.currentTimeMillis())

    val server = WebShowServer(
        port, size, size,
        strategyName = strategyName,
        adminToken = System.getProperty("adminToken"),
    )
    val area = size * size
    var round = 0L

    while (true) {
        val seed = baseSeed + round
        server.newGame(round, seed)
        val gameStart = System.currentTimeMillis()
        val result = GameRunner.play(
            config = GameConfig(width = size, height = size, seed = seed),
            strategy = Strategies.create(strategyName, Random(seed)),
        ) { game ->
            server.render(game)
            // Zero viewers: pause the game outright — the 24/7 box burns nothing
            // until someone opens the page, and the show resumes mid-frame.
            while (server.viewers() == 0) Thread.sleep(500)
            // Finale slow-motion, same feel as the desktop show.
            val free = area - game.score
            val effectiveTps = if (free > 50) tps else maxOf(250, tps * free / 50)
            val sleepEvery = maxOf(1, effectiveTps / 1000)
            if (effectiveTps < 1_000_000 && game.steps % sleepEvery == 0) Thread.sleep(1)
        }
        server.gameFinished(round, result, System.currentTimeMillis() - gameStart)
        println("round $round: $result")
        round++
        Thread.sleep(2_500)
    }
}
