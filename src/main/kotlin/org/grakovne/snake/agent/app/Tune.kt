package org.grakovne.snake.agent.app

import org.grakovne.snake.agent.core.GameConfig
import org.grakovne.snake.agent.core.GameStatus
import org.grakovne.snake.agent.sim.Arena
import org.grakovne.snake.agent.strategy.SafeGreedyKnobs
import org.grakovne.snake.agent.strategy.SafeGreedyStrategy
import kotlin.random.Random

/**
 * Parameter search over SafeGreedy knobs: every configuration plays the same seed set in
 * parallel, the leaderboard is ranked by target rate (score >= area - 1).
 *
 * ./gradlew tune -Psize=15 -Pgames=200 -Pseed=1000
 */
fun main() {
    val size = intProp("size", 15)
    val games = intProp("games", 200)
    val seed = longProp("seed", 1000)
    val parallelism = intProp("parallelism", Runtime.getRuntime().availableProcessors())

    // Round 3: refinement around A+noTimed (66.8% on 15x15 in round 2).
    val b = SafeGreedyKnobs(
        stallCommitMidgame = false, avoidAroundFood = false,
        guardDeadCells = false, timedCandidate = false,
    )
    // Round 5: timed candidate as a hunt rescue after a fraction of the budget.
    val configs = linkedMapOf(
        "B" to b,
        "B+rescue15" to b.copy(timedRescuePercent = 15),
        "B+rescue30" to b.copy(timedRescuePercent = 30),
        "B+rescue50" to b.copy(timedRescuePercent = 50),
        "B+rescue70" to b.copy(timedRescuePercent = 70),
        "B+rescue30div10" to b.copy(timedRescuePercent = 30, endgameDivisor = 10),
    )

    println("tune: field=${size}x$size games=$games seed=$seed configs=${configs.size}")
    val area = size * size
    val startedAt = System.nanoTime()

    val leaderboard = Arena(parallelism).tournament(
        candidates = configs.keys.toList(),
        config = GameConfig(width = size, height = size, seed = seed),
        gamesPerCandidate = games,
    ) { name, index ->
        SafeGreedyStrategy(
            timeAware = false,
            guardHoles = true,
            random = Random(seed + index),
            knobs = configs.getValue(name),
        )
    }

    val rows = leaderboard.map { (name, evaluation) ->
        val target = evaluation.results.count { it.score >= area - 1 }
        val wins = evaluation.results.count { it.status == GameStatus.WON }
        Triple(name, target, Pair(wins, evaluation))
    }.sortedByDescending { it.second }

    println(
        "%-16s %8s %6s %8s %6s %6s".format("config", "target%", "wins", "mean", "min", "median")
    )
    for ((name, target, rest) in rows) {
        val (wins, evaluation) = rest
        println(
            "%-16s %7.1f%% %6d %8.1f %6d %6.1f".format(
                name, 100.0 * target / games, wins,
                evaluation.meanScore, evaluation.minScore, evaluation.medianScore,
            )
        )
    }
    println("time: %.1fs".format((System.nanoTime() - startedAt) / 1e9))
}
