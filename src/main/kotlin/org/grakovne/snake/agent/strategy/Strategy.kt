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
        "sweep", "learned", "mc", "sweepMidChaos", "sweepEndChaos",
        "episodes", "neural", "neuralstall", "repairguard", "holerank",
        "band2", "lanes", "laneband",
        "chor0", "chor0e", "sortstall", "sorteats", "endsolver", "showman",
    )

    fun create(name: String, random: Random = Random.Default): Strategy = when (name) {
        "greedy" -> GreedyStrategy()
        // SHOW-ONLY: guaranteed full board via a mutating Hamiltonian cycle.
        // Banned from research comparisons — it wins by construction.
        "showman" -> ShowmanStrategy(random)
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
        // Mean-shifters (night synthesis): hard band contiguity / serpentine lanes.
        "band2" -> SafeGreedyStrategy(
            timeAware = false, guardHoles = true, random = random,
            knobs = SafeGreedyKnobs(
                stallCommitMidgame = false, avoidAroundFood = false,
                guardDeadCells = false, timedCandidate = false,
                bandFree = intProp("bandFree", 60),
            ),
        )
        "lanes" -> SafeGreedyStrategy(
            timeAware = false, guardHoles = true, random = random,
            knobs = SafeGreedyKnobs(
                stallCommitMidgame = false, avoidAroundFood = false,
                guardDeadCells = false, timedCandidate = false,
                laneBias = true,
            ),
        )
        "laneband" -> SafeGreedyStrategy(
            timeAware = false, guardHoles = true, random = random,
            knobs = SafeGreedyKnobs(
                stallCommitMidgame = false, avoidAroundFood = false,
                guardDeadCells = false, timedCandidate = false,
                laneBias = true, bandFree = intProp("bandFree", 60),
            ),
        )
        // Choreography v0: follow the shape instead of evaluating positions.
        // Committed serpentine stall walks in the midgame (deterministic bias 0,
        // no chaos), serpentine eat-walks; endgame keeps per-tick chaotic replans
        // (measured load-bearing). Untested combo: every band/lane variant so far
        // ran on top of CHAOTIC stalling.
        "chor0" -> SafeGreedyStrategy(
            timeAware = false, guardHoles = true, random = random,
            knobs = SafeGreedyKnobs(
                stallCommitMidgame = true, chaosMidgame = false,
                avoidAroundFood = false, guardDeadCells = false,
                timedCandidate = false, laneBias = true,
            ),
        )
        // chor0 discipline + endgame episode seed search (champion working point).
        "chor0e" -> SafeGreedyStrategy(
            timeAware = false, guardHoles = true, random = random,
            knobs = SafeGreedyKnobs(
                stallCommitMidgame = true, chaosMidgame = false,
                avoidAroundFood = false, guardDeadCells = false,
                timedCandidate = false, laneBias = true,
                episodeSeeds = intProp("episodeSeeds", 4),
                episodeRollouts = intProp("episodeRollouts", 3),
                episodeFree = intProp("episodeFree", 40),
            ),
        )
        // Champion knobs + sorting-stall: per-tick replan kept, but the four stall
        // shapes are judged by free-space fragmentation at walk end instead of a die.
        "sortstall" -> SafeGreedyStrategy(
            timeAware = false, guardHoles = true, random = random,
            knobs = SafeGreedyKnobs(
                stallCommitMidgame = false, avoidAroundFood = false,
                guardDeadCells = false, timedCandidate = false,
                sortStall = true,
            ),
        )
        // Champion knobs + exact endgame solver in the last solverFree cells.
        "endsolver" -> SafeGreedyStrategy(
            timeAware = false, guardHoles = true, random = random,
            knobs = SafeGreedyKnobs(
                stallCommitMidgame = false, avoidAroundFood = false,
                guardDeadCells = false, timedCandidate = false,
                solverFree = intProp("solverFree", 10),
                solverBudget = intProp("solverBudget", 150_000),
            ),
        )
        // Champion knobs + midgame eat-walk ranking by post-eat fragmentation.
        "sorteats" -> SafeGreedyStrategy(
            timeAware = false, guardHoles = true, random = random,
            knobs = SafeGreedyKnobs(
                stallCommitMidgame = false, avoidAroundFood = false,
                guardDeadCells = false, timedCandidate = false,
                sortEats = true,
            ),
        )
        // Champion with isolated-hole count as the primary endgame objective
        // (data-driven: hole count separates solved/doomed with AUC 0.85).
        "holerank" -> SafeGreedyStrategy(
            timeAware = false, guardHoles = true, random = random,
            knobs = SafeGreedyKnobs(
                stallCommitMidgame = false, avoidAroundFood = false,
                guardDeadCells = false, timedCandidate = false,
                rankHoles = true,
            ),
        )
        // Champion plus theory-derived repair-need guard/ranking at eats.
        "repairguard" -> SafeGreedyStrategy(
            timeAware = false, guardHoles = true, random = random,
            knobs = SafeGreedyKnobs(
                stallCommitMidgame = false, avoidAroundFood = false,
                guardDeadCells = false, timedCandidate = false,
                guardRepair = true,
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
