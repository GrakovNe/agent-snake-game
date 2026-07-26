package org.grakovne.snake.agent.strategy

import org.grakovne.snake.agent.core.Direction
import org.grakovne.snake.agent.core.GameView
import kotlin.random.Random

/**
 * A bot. May be stateful, so every game gets its own instance (see the factory
 * parameters of Arena/GameRunner call sites). For reproducible runs give the
 * instance a seeded [Random].
 */
fun interface Strategy {
    fun nextMove(game: GameView): Direction
}

object Strategies {
    val names = listOf(
        "greedy", "random", "safe", "safetime", "hug", "band",
        "sweep", "learned", "mc", "sweepMidChaos", "sweepEndChaos", "episodes", "neural", "neuralstall",
    )

    fun create(name: String, random: Random = Random.Default): Strategy = when (name) {
        "greedy" -> GreedyStrategy()
        "random" -> RandomStrategy(random)
        "safe" -> SafeGreedyStrategy(timeAware = false)
        "safetime" -> SafeGreedyStrategy(timeAware = true)
        "hug" -> SafeGreedyStrategy(timeAware = false, hugging = true)
        "band" -> SafeGreedyStrategy(timeAware = false, hugging = true, guardHoles = true, random = random)
        // Tournament champion (tune rounds 1-5): no midgame stall commitment, no
        // food-perimeter avoidance, no midgame dead-cell guard, no first-line timed
        // candidate; endgame digestibility guard and sweeps on.
        "sweep" -> SafeGreedyStrategy(
            timeAware = false,
            guardHoles = true,
            random = random,
            knobs = SafeGreedyKnobs(
                stallCommitMidgame = false,
                avoidAroundFood = false,
                guardDeadCells = false,
                timedCandidate = false,
            ),
        )
        // Champion knobs plus endgame episode seed search (exact-replay best-of-k).
        "episodes" -> SafeGreedyStrategy(
            timeAware = false, guardHoles = true, random = random,
            knobs = SafeGreedyKnobs(
                stallCommitMidgame = false, avoidAroundFood = false,
                guardDeadCells = false, timedCandidate = false,
                // Working point: k4/m3 saturates the search (k6/m5 measured no better),
                // episodeFree=40 keeps 60x60 rollout costs sane.
                episodeSeeds = intProp("episodeSeeds", 4),
                episodeRollouts = intProp("episodeRollouts", 3),
                episodeFree = intProp("episodeFree", 40),
            ),
        )
        // Neural episode search plus value-guided stall-lap shaping.
        "neuralstall" -> SafeGreedyStrategy(
            timeAware = false, guardHoles = true, random = random,
            knobs = SafeGreedyKnobs(
                stallCommitMidgame = false, avoidAroundFood = false,
                guardDeadCells = false, timedCandidate = false,
                episodeSeeds = intProp("episodeSeeds", 4),
                episodeFree = intProp("episodeFree", 40),
                valueNetPath = prop("valueNet", "data/value-net.onnx"),
                valueStall = true,
            ),
        )
        // Episode search with the ONNX value net instead of continuation rollouts.
        "neural" -> SafeGreedyStrategy(
            timeAware = false, guardHoles = true, random = random,
            knobs = SafeGreedyKnobs(
                stallCommitMidgame = false, avoidAroundFood = false,
                guardDeadCells = false, timedCandidate = false,
                episodeSeeds = intProp("episodeSeeds", 4),
                episodeFree = intProp("episodeFree", 40),
                valueNetPath = prop("valueNet", "data/value-net.onnx"),
            ),
        )
        // Variance-attribution variants: chaos only in one phase.
        "sweepMidChaos" -> SafeGreedyStrategy(
            timeAware = false, guardHoles = true, random = random,
            knobs = SafeGreedyKnobs(
                stallCommitMidgame = false, avoidAroundFood = false,
                guardDeadCells = false, timedCandidate = false,
                chaosEndgame = false,
            ),
        )
        "sweepEndChaos" -> SafeGreedyStrategy(
            timeAware = false, guardHoles = true, random = random,
            knobs = SafeGreedyKnobs(
                stallCommitMidgame = false, avoidAroundFood = false,
                guardDeadCells = false, timedCandidate = false,
                chaosMidgame = false,
            ),
        )
        // Champion knobs plus Monte-Carlo eat selection in the frozen endgame.
        "mc" -> SafeGreedyStrategy(
            timeAware = false,
            guardHoles = true,
            random = random,
            knobs = SafeGreedyKnobs(
                stallCommitMidgame = false,
                avoidAroundFood = false,
                guardDeadCells = false,
                timedCandidate = false,
                rolloutFree = intProp("rolloutFree", 12),
                rolloutCount = intProp("rolloutCount", 3),
            ),
        )
        // Champion knobs plus the learned loop-shape ranker (weights from data/weights.txt).
        "learned" -> SafeGreedyStrategy(
            timeAware = false,
            guardHoles = true,
            random = random,
            knobs = SafeGreedyKnobs(
                stallCommitMidgame = false,
                avoidAroundFood = false,
                guardDeadCells = false,
                timedCandidate = false,
                valueWeights = loadWeights(),
            ),
        )
        else -> error("unknown strategy '$name', available: $names")
    }

    private fun intProp(name: String, default: Int): Int =
        System.getProperty(name)?.toInt() ?: default

    private fun prop(name: String, default: String): String =
        System.getProperty(name) ?: default

    private var cachedWeights: DoubleArray? = null

    @Synchronized
    private fun loadWeights(): DoubleArray {
        cachedWeights?.let { return it }
        val file = java.io.File("data/weights.txt")
        require(file.exists()) { "data/weights.txt not found; run collect + fit first" }
        return file.readText().trim().split(",").map { it.toDouble() }.toDoubleArray()
            .also { cachedWeights = it }
    }
}
