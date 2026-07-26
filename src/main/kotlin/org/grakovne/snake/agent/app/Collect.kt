package org.grakovne.snake.agent.app

import org.grakovne.snake.agent.core.GameConfig
import org.grakovne.snake.agent.sim.Arena
import org.grakovne.snake.agent.strategy.SafeGreedyKnobs
import org.grakovne.snake.agent.strategy.SafeGreedyStrategy
import java.io.File
import kotlin.random.Random

/**
 * Self-play dataset for the loop-shape value function: every endgame eat logs the
 * features of the accepted post-eat loop; the game's outcome (reached area-1 or not)
 * labels all of its rows. Output: data/loops-<size>.csv
 *
 * ./gradlew collect -Psize=30 -Pgames=1000 -Pseed=9000
 */
fun main() {
    val size = intProp("size", 30)
    val games = intProp("games", 1000)
    val seed = longProp("seed", 9000)
    val parallelism = intProp("parallelism", Runtime.getRuntime().availableProcessors())

    val knobs = SafeGreedyKnobs(
        stallCommitMidgame = false, avoidAroundFood = false,
        guardDeadCells = false, timedCandidate = false,
    )

    val perGame = arrayOfNulls<MutableList<DoubleArray>>(games)
    val evaluation = Arena(parallelism).evaluate(
        config = GameConfig(width = size, height = size, seed = seed),
        games = games,
    ) { index ->
        val rows = ArrayList<DoubleArray>()
        perGame[index] = rows
        SafeGreedyStrategy(
            timeAware = false,
            guardHoles = true,
            random = Random(seed + index),
            knobs = knobs,
        ).also { strategy ->
            strategy.eatObserver = { features -> rows.add(features.copyOf()) }
        }
    }

    val target = size * size - 1
    val out = File("data/loops-$size.csv")
    out.parentFile.mkdirs()
    var rows = 0
    var positives = 0
    out.bufferedWriter().use { writer ->
        evaluation.results.forEachIndexed { index, result ->
            val label = if (result.score >= target) 1.0 else 0.0
            if (label > 0.5) positives += perGame[index]!!.size
            for (features in perGame[index]!!) {
                writer.write(features.joinToString(",") + "," + label + "\n")
                rows++
            }
        }
    }
    println("wrote $rows rows (${positives} positive) to ${out.path}")
    println("games at target: ${evaluation.results.count { it.score >= target }}/$games")
}
