package org.grakovne.snake.agent.sim

import org.grakovne.snake.agent.core.GameConfig
import org.grakovne.snake.agent.core.GameResult
import org.grakovne.snake.agent.core.GameStatus
import org.grakovne.snake.agent.core.SnakeGame
import org.grakovne.snake.agent.strategy.Strategy

object GameRunner {

    /**
     * Plays a single game to the end. [onStep] is called after the initial state
     * and after every step — the UI hooks in here; headless runs pass nothing.
     */
    fun play(
        config: GameConfig,
        strategy: Strategy,
        onStep: ((SnakeGame) -> Unit)? = null,
    ): GameResult {
        val game = SnakeGame(config)
        onStep?.invoke(game)

        while (game.status == GameStatus.RUNNING) {
            game.step(strategy.nextMove(game))
            onStep?.invoke(game)
        }

        return game.result()
    }
}
