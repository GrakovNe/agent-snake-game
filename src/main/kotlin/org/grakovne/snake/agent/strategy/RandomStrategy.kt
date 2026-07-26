package org.grakovne.snake.agent.strategy

import org.grakovne.snake.agent.core.Direction
import org.grakovne.snake.agent.core.GameView
import kotlin.random.Random

/** Weakest baseline: a uniformly random move among the ones that do not kill immediately. */
class RandomStrategy(private val random: Random = Random.Default) : Strategy {

    override fun nextMove(game: GameView): Direction {
        val legal = game.legalMoves()
        return if (legal.isEmpty()) game.heading else legal.random(random)
    }
}
