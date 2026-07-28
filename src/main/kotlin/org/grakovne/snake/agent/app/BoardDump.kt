package org.grakovne.snake.agent.app

import org.grakovne.snake.agent.core.GameConfig
import org.grakovne.snake.agent.core.GameStatus
import org.grakovne.snake.agent.core.SnakeGame
import org.grakovne.snake.agent.strategy.Strategies
import java.io.File
import kotlin.random.Random

/**
 * Dumps board snapshots of a real game at chosen fill levels — raw material for
 * the documentation figures. Line format: `fillPercent food body...` (cell indices).
 *
 * ./gradlew boarddump -Psize=60 -Pseed=137 -Pout=data/boards-60.txt
 */
fun main() {
    val size = intProp("size", 60)
    val seed = longProp("seed", 137)
    val out = prop("out", "data/boards-$size.txt")
    val fills = intArrayOf(15, 50, 93, 99)

    val game = SnakeGame(GameConfig(width = size, height = size, seed = seed))
    val bot = Strategies.create(prop("strategy", "sweep"), Random(seed))
    val area = size * size
    var next = 0
    val sb = StringBuilder()
    while (game.status == GameStatus.RUNNING && next < fills.size) {
        game.step(bot.nextMove(game))
        if (100 * game.score >= fills[next] * area) {
            sb.append(fills[next]).append(' ')
                .append(game.food.y * size + game.food.x)
            for (p in game.snake) sb.append(' ').append(p.y * size + p.x)
            sb.append('\n')
            next++
        }
    }
    File(out).writeText(sb.toString())
    println("dumped ${next} snapshots to $out (final score ${game.score})")
}
