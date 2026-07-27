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

    // Fairy mode: every round is a REAL honest game under a pre-searched spawn
    // script (the benevolent food fairy) — the bot is genuine and every game is
    // unique; only its luck is loaded. Script lines: "<strategy> <botSeed> <c,c,..>".
    data class Scripted(val strategy: String, val botSeed: Long, val spawns: List<org.grakovne.snake.agent.core.Position>)
    val scripts: List<Scripted> = if (strategyName == "fairy") {
        val file = File(prop("winners", "data/fairy-60.txt"))
        val lines = when {
            file.exists() -> file.readLines()
            else -> object {}.javaClass.getResource("/fairy-60.txt")
                ?.readText()?.lines().orEmpty()
        }
        lines.mapNotNull { line ->
            val parts = line.trim().split(" ")
            if (parts.size != 3) return@mapNotNull null
            val spawns = parts[2].split(",").map { idx ->
                val v = idx.toInt()
                org.grakovne.snake.agent.core.Position(v % size, v / size)
            }
            Scripted(parts[0], parts[1].toLong(), spawns)
        }.also {
            require(it.isNotEmpty()) { "fairy mode needs a non-empty scripts file" }
            println("webshow: fairy library of ${"$"}{it.size} scripted honest wins")
        }
    } else {
        emptyList()
    }

    val server = WebShowServer(
        port, size, size,
        strategyName = strategyName,
        adminToken = System.getProperty("adminToken"),
    )
    val area = size * size
    var round = 0L

    val pickRng = Random(baseSeed)
    while (true) {
        val script = if (scripts.isNotEmpty()) scripts[pickRng.nextInt(scripts.size)] else null
        val playName = script?.strategy ?: strategyName
        val seed = script?.botSeed ?: (baseSeed + round)
        server.newGame(round, seed)
        val gameStart = System.currentTimeMillis()
        val result = GameRunner.play(
            config = GameConfig(width = size, height = size, seed = seed),
            strategy = Strategies.create(playName, Random(seed)),
            spawnScript = script?.spawns,
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
