package org.grakovne.snake.agent.sim

import org.grakovne.snake.agent.core.GameConfig
import org.grakovne.snake.agent.core.GameStatus
import org.grakovne.snake.agent.core.Position
import org.grakovne.snake.agent.core.SnakeGame
import org.grakovne.snake.agent.strategy.SafeGreedyKnobs
import org.grakovne.snake.agent.strategy.SafeGreedyStrategy
import kotlin.random.Random

/** Real-engine rollouts from arbitrary states, used for train-time labeling. */
object Rollouts {

    val championKnobs = SafeGreedyKnobs(
        stallCommitMidgame = false,
        avoidAroundFood = false,
        guardDeadCells = false,
        timedCandidate = false,
    )

    /**
     * Final score of one championship-policy playout from the given body state.
     * [starvationBudget] caps hunts inside the rollout: a reduced budget makes labels
     * uniformly slightly pessimistic but dramatically cheaper on big boards.
     */
    fun playOut(
        width: Int,
        height: Int,
        body: List<Position>,
        seed: Long,
        starvationBudget: Int = width * height * 2,
    ): Int {
        val game = SnakeGame(
            GameConfig(
                width = width, height = height, seed = seed,
                maxStepsWithoutFood = starvationBudget,
            ),
            initialBody = body,
        )
        val policy = SafeGreedyStrategy(
            timeAware = false,
            guardHoles = true,
            random = Random(seed),
            knobs = championKnobs,
        )
        while (game.status == GameStatus.RUNNING) {
            game.step(policy.nextMove(game))
        }
        return game.score
    }
}
