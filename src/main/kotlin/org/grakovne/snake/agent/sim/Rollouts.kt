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
     * Policies for self-play and labeling:
     * champion — the tuned heuristic bot;
     * neural   — champion + net-driven episode search (the ExIt teacher);
     * netstall — champion + value-guided stalling only (cheap improved rollouts).
     */
    fun policyFor(name: String, seed: Long): SafeGreedyStrategy {
        val knobs = when (name) {
            "neural" -> championKnobs.copy(
                episodeSeeds = 4, episodeFree = 40, valueNetPath = "data/value-net.onnx",
            )
            "netstall" -> championKnobs.copy(
                valueNetPath = "data/value-net.onnx", valueStall = true,
            )
            else -> championKnobs
        }
        return SafeGreedyStrategy(
            timeAware = false, guardHoles = true,
            random = Random(seed), knobs = knobs,
        )
    }

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
        policyName: String = "champion",
    ): Int {
        val game = SnakeGame(
            GameConfig(
                width = width, height = height, seed = seed,
                maxStepsWithoutFood = starvationBudget,
            ),
            initialBody = body,
        )
        val policy = policyFor(policyName, seed)
        while (game.status == GameStatus.RUNNING) {
            game.step(policy.nextMove(game))
        }
        return game.score
    }
}
