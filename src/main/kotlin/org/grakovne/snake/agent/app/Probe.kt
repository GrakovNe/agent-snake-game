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
        val result = GameRunner.play(
            GameConfig(width = size, height = size, seed = gameSeed),
            Strategies.create(strategyName, Random(gameSeed)),
        ) { game ->
            lastGame = game
            if (firstHoleStep == -1 && game.score > game.width * game.height / 2 &&
                deadFreeCells(game) > 0
            ) {
                firstHoleStep = game.steps
                firstHoleScore = game.score
                firstHoleBoard = render(game)
            }
        }

        if (result.status == GameStatus.WON) {
            println("seed=$gameSeed WON in ${result.steps} steps (first hole: step=$firstHoleStep score=$firstHoleScore)")
            continue
        }
        lost++
        val game = lastGame!!
        println(
            "seed=$gameSeed DEAD ${result.deathReason} score=${result.score}/${size * size} " +
                "steps=${result.steps} sinceFood=${game.stepsSinceFood} " +
                "firstHole: step=$firstHoleStep score=$firstHoleScore"
        )
        println("-- first hole board --")
        println(firstHoleBoard)
        println("-- terminal board --")
        println(render(game))
    }
    println("lost $lost of $games")
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
