package org.grakovne.snake.agent.app

import org.grakovne.snake.agent.core.GameConfig
import org.grakovne.snake.agent.core.GameStatus
import org.grakovne.snake.agent.core.SnakeGame
import org.grakovne.snake.agent.strategy.Strategies
import java.util.Locale
import kotlin.random.Random

/**
 * Turing test for the showman: trajectory statistics that a viewer (or a
 * discriminator) could use to tell a guaranteed Hamiltonian rider from an
 * honest bot. Prints turn rate, straight-run lengths and food-approach
 * efficiency (steps per eat vs the Manhattan lower bound) by fill phase.
 *
 * ./gradlew turing -Psize=30 -Pgames=20 -Pstrategy=showman
 */
fun main() {
    val size = intProp("size", 30)
    val games = intProp("games", 20)
    val seed = longProp("seed", 42)
    val strategyName = prop("strategy", "sweep")

    val area = size * size
    // fill phases: opening, midgame, endgame, carousel
    val phases = listOf(0 to 50, 50 to 90, 90 to 99, 99 to 101)
    val turns = LongArray(phases.size)
    val stepsInPhase = LongArray(phases.size)
    val eatSteps = Array(phases.size) { ArrayList<Int>() }
    val eatExcess = Array(phases.size) { ArrayList<Double>() }
    val runs = ArrayList<Int>()

    repeat(games) { g ->
        val game = SnakeGame(GameConfig(width = size, height = size, seed = seed + g))
        val strategy = Strategies.create(strategyName, Random(seed + g))
        var prevDir = game.heading
        var run = 0
        var sinceEat = 0
        var eatStart = game.head
        var lastScore = game.score
        while (game.status == GameStatus.RUNNING) {
            val fill = 100 * game.score / area
            val phase = phases.indexOfFirst { fill >= it.first && fill < it.second }.coerceAtLeast(0)
            val dir = strategy.nextMove(game)
            stepsInPhase[phase]++
            sinceEat++
            if (dir != prevDir) {
                turns[phase]++
                if (run > 0) runs.add(run)
                run = 0
            } else {
                run++
            }
            prevDir = dir
            game.step(dir)
            if (game.score != lastScore) {
                val manhattan = Math.abs(eatStart.x - game.head.x) + Math.abs(eatStart.y - game.head.y)
                eatSteps[phase].add(sinceEat)
                eatExcess[phase].add(sinceEat.toDouble() / maxOf(1, manhattan))
                lastScore = game.score
                sinceEat = 0
                eatStart = game.head
            }
        }
    }

    println("turing: strategy=$strategyName field=${size}x$size games=$games")
    org.grakovne.snake.agent.strategy.ShowmanStrategy.let {
        println(
            "steer: tried=" + org.grakovne.snake.agent.strategy.ShowmanStrategy.steerTried.get() +
                " ok=" + org.grakovne.snake.agent.strategy.ShowmanStrategy.steerOk.get() +
                " noChord=" + org.grakovne.snake.agent.strategy.ShowmanStrategy.steerNoChord.get() +
                " blocked=" + org.grakovne.snake.agent.strategy.ShowmanStrategy.steerBlocked.get() +
                " noDesired=" + org.grakovne.snake.agent.strategy.ShowmanStrategy.noDesired.get()
        )
    }
    println(
        "%-12s %10s %14s %16s".format(Locale.ROOT, "phase", "turns/100", "steps/eat p50", "excess-vs-bee p50")
    )
    for (i in phases.indices) {
        val label = "${phases[i].first}-${minOf(100, phases[i].second)}%"
        val turnRate = if (stepsInPhase[i] > 0) 100.0 * turns[i] / stepsInPhase[i] else 0.0
        val med = eatSteps[i].sorted().let { if (it.isEmpty()) 0 else it[it.size / 2] }
        val exc = eatExcess[i].sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
        println("%-12s %10.1f %14d %16.1f".format(Locale.ROOT, label, turnRate, med, exc))
    }
    val sortedRuns = runs.sorted()
    println(
        "straight runs: p50=%d p95=%d max=%d".format(
            Locale.ROOT,
            sortedRuns[sortedRuns.size / 2],
            sortedRuns[(sortedRuns.size * 95) / 100],
            sortedRuns.last(),
        )
    )
}
