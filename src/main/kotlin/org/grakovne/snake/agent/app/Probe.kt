package org.grakovne.snake.agent.app

import org.grakovne.snake.agent.core.GameConfig
import org.grakovne.snake.agent.core.GameStatus
import org.grakovne.snake.agent.core.Position
import org.grakovne.snake.agent.core.SnakeGame
import org.grakovne.snake.agent.sim.GameRunner
import org.grakovne.snake.agent.strategy.Strategies
import kotlin.random.Random

/**
 * Research instrument: plays games sequentially and dumps the terminal board of every
 * lost game as ASCII plus death context, to study failure modes.
 *
 * ./gradlew probe -Psize=15 -Pgames=30 -Pstrategy=safe -Pseed=42
 */
fun main() {
    val size = intProp("size", 15)
    val games = intProp("games", 30)
    val seed = longProp("seed", 42)
    val strategyName = prop("strategy", "safe")

    var lost = 0
    for (index in 0 until games) {
        val gameSeed = seed + index
        var lastGame: SnakeGame? = null
        var firstHoleStep = -1
        var firstHoleScore = -1
        var firstHoleBoard = ""
        val huntDumpEvery = intProp("huntDump", 0)
        val autopsyFrames = intProp("autopsy", 0)
        val autopsyEvery = intProp("autopsyEvery", 1)
        val trackUndigestible = intProp("undig", 0) == 1
        val ring = ArrayDeque<String>()
        var lastUndigestible = 0
        val strategy = Strategies.create(strategyName, Random(gameSeed))
        val result = GameRunner.play(
            GameConfig(width = size, height = size, seed = gameSeed),
            strategy,
        ) { game ->
            lastGame = game
            if (firstHoleStep == -1 && game.score > game.width * game.height / 2 &&
                deadFreeCells(game) > 0
            ) {
                firstHoleStep = game.steps
                firstHoleScore = game.score
                firstHoleBoard = render(game)
            }
            if (trackUndigestible && game.score > game.width * game.height * 8 / 10) {
                val current = undigestibleHoles(game)
                if (current != lastUndigestible) {
                    val context = if (game.stepsSinceFood == 0) "AT-EAT" else "MID-STALL(${game.stepsSinceFood})"
                    println(
                        "undig ${lastUndigestible}->${current} step=${game.steps} " +
                            "score=${game.score} $context"
                    )
                    if (current > lastUndigestible) println(render(game))
                    lastUndigestible = current
                }
            }
            if (autopsyFrames > 0 && game.steps % autopsyEvery == 0) {
                if (ring.size == autopsyFrames) ring.removeFirst()
                ring.addLast(
                    "step=${game.steps} score=${game.score} sinceFood=${game.stepsSinceFood}\n" +
                        render(game)
                )
            }
            if (huntDumpEvery > 0 && game.stepsSinceFood > 0 &&
                game.stepsSinceFood % huntDumpEvery == 0 &&
                game.score > game.width * game.height * 9 / 10
            ) {
                println("hunt: score=${game.score} sinceFood=${game.stepsSinceFood} steps=${game.steps}")
                println(render(game))
            }
        }

        val counters = (strategy as? org.grakovne.snake.agent.strategy.SafeGreedyStrategy)?.let {
            " timed=${it.timedCommits} hunts=${it.huntCommits} chains=${it.escapeChains} " +
                "midwalk=${it.midwalkInvalidations} desperate=${it.desperationEats}"
        } ?: ""

        if (result.status == GameStatus.WON) {
            println(
                "seed=$gameSeed WON in ${result.steps} steps " +
                    "(first hole: step=$firstHoleStep score=$firstHoleScore)$counters"
            )
            continue
        }
        lost++
        val game = lastGame!!
        val foodDead = org.grakovne.snake.agent.core.Direction.entries.none { d ->
            game.contains(game.food + d) && !game.isOccupied(game.food + d)
        }
        println(
            "seed=$gameSeed DEAD ${result.deathReason} score=${result.score}/${size * size} " +
                "steps=${result.steps} sinceFood=${game.stepsSinceFood} " +
                "firstHole: step=$firstHoleStep score=$firstHoleScore$counters " +
                "undigAtDeath=${undigestibleHoles(game)} foodInDeadCell=$foodDead"
        )
        if (autopsyFrames > 0) {
            println("-- autopsy (${ring.size} frames) --")
            ring.forEach(::println)
        } else {
            println("-- first hole board --")
            println(firstHoleBoard)
            println("-- terminal board --")
            println(render(game))
        }
        ring.clear()
    }
    println("lost $lost of $games")
}

/** Schedule-aware undigestible holes, mirroring BoardSearch.undigestibleHoles. */
private fun undigestibleHoles(game: SnakeGame): Int {
    val loop = game.snake.size
    val gap = game.width * game.height - loop
    if (gap <= 1) return 0
    val vacate = HashMap<Position, Int>(loop * 2)
    game.snake.forEachIndexed { index, cell -> vacate[cell] = loop - index }
    var undigestible = 0
    for (y in 0 until game.height) {
        cells@ for (x in 0 until game.width) {
            val cell = Position(x, y)
            if (vacate.containsKey(cell)) continue
            val bodyNeighbors = ArrayList<Int>(4)
            for (direction in org.grakovne.snake.agent.core.Direction.entries) {
                val next = cell + direction
                if (!game.contains(next)) continue
                val b = vacate[next] ?: continue@cells   // free neighbor: cluster slack
                bodyNeighbors.add(b)
            }
            if (bodyNeighbors.size < 2) {
                undigestible++
                continue
            }
            for (i in bodyNeighbors.indices) {
                for (j in i + 1 until bodyNeighbors.size) {
                    val forward = (bodyNeighbors[i] - bodyNeighbors[j]).mod(loop)
                    if (forward < gap || loop - forward < gap) continue@cells
                }
            }
            undigestible++
        }
    }
    return undigestible
}

/** Free cells (food included) with no free neighbors. */
private fun deadFreeCells(game: SnakeGame): Int {
    var dead = 0
    for (y in 0 until game.height) {
        cells@ for (x in 0 until game.width) {
            val position = Position(x, y)
            if (game.isOccupied(position)) continue
            for (direction in org.grakovne.snake.agent.core.Direction.entries) {
                val neighbor = position + direction
                if (game.contains(neighbor) && !game.isOccupied(neighbor)) continue@cells
            }
            dead++
        }
    }
    return dead
}

private fun render(game: SnakeGame): String = buildString {
    val body = game.snake.toHashSet()
    for (y in 0 until game.height) {
        for (x in 0 until game.width) {
            val position = Position(x, y)
            append(
                when {
                    position == game.head -> 'H'
                    position == game.snake.last() -> 't'
                    position in body -> 'o'
                    position == game.food -> 'F'
                    else -> '.'
                }
            )
        }
        appendLine()
    }
}
