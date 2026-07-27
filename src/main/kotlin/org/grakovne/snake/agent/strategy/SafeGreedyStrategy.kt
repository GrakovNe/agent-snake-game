package org.grakovne.snake.agent.strategy

import org.grakovne.snake.agent.core.Direction
import org.grakovne.snake.agent.core.GameView
import org.grakovne.snake.agent.core.Position
import org.grakovne.snake.agent.strategy.search.BoardSearch

/**
 * Tail-reachability greedy with a portfolio of eating walks.
 *
 * The endgame observation driving the design: once the board is packed, stalling is a
 * rigid zero-gap tail-chase that changes nothing — the trajectory loop reshapes only at
 * the moment of eating. So the shape of the eating walk is where fragmentation of the
 * free space is decided, and fragmented free cells are where later food strands and
 * kills. Per food the bot therefore generates several candidate walks (shortest,
 * wall-hugging, sweeping detour variants), filters them for safety and picks the one
 * leaving the free space least fragmented.
 *
 * Safety of a candidate: after virtually eating, either the tail is statically reachable
 * (a renewable invariant — the snake can stall indefinitely), or a concrete timed escape
 * walk exists that itself ends statically safe. Timed reachability is never trusted as a
 * survivability check on its own (the head cannot idle), only as a committed walk whose
 * timings are exact in a deterministic engine.
 *
 * When no candidate is safe: stall along the longest static path to the own tail with
 * chaotic per-tick reshaping, re-checking acceptance every tick; graded desperation near
 * the starvation limit.
 */
/** Tunable knobs of [SafeGreedyStrategy]; defaults reproduce the hand-tuned baseline. */
data class SafeGreedyKnobs(
    /** endgame when freeCells <= max(8, area / endgameDivisor) */
    val endgameDivisor: Int = 20,
    /** commit to stall walks outside the endgame instead of replanning per tick */
    val stallCommitMidgame: Boolean = true,
    /** randomize the stall extension bias per tick */
    val chaosStall: Boolean = true,
    /** phase split of the stall chaos (variance-attribution experiments) */
    val chaosMidgame: Boolean = true,
    val chaosEndgame: Boolean = true,
    /** soft-block the cells around the food while stalling */
    val avoidAroundFood: Boolean = true,
    /** endgame guard: post-eat undigestible-hole count must not grow */
    val guardUndigestible: Boolean = true,
    /** midgame guard: post-eat dead-cell count must not grow */
    val guardDeadCells: Boolean = true,
    /** number of sweeping longest-path food candidates in the endgame */
    val sweepVariants: Int = 4,
    /** reconstruct shortest food paths hugging walls and body */
    val hugging: Boolean = true,
    /** add the timed corridor food path candidate in the endgame */
    val timedCandidate: Boolean = true,
    /** with <= 2 free cells, eat unsafely after this fraction of the budget (x100) */
    val patiencePercent: Int = 50,
    /** enable the timed food candidate after this fraction of the budget (0 = never) */
    val timedRescuePercent: Int = 0,
    /** desperation when remaining budget < path + desperationMargin * (w + h) */
    val desperationMargin: Int = 2,
    /** directed detour-insertion beam repair when all candidates are guard-rejected */
    val shaper: Boolean = true,
    /** required overlap (ticks) of hole-wall free intervals to count a hole digestible */
    val digestSlack: Int = 1,
    /** with <= this many free cells, rank candidates by provably-edible future spawns */
    val minimaxFree: Int = 16,
    /** learned linear value of loop features; when set, ranks endgame candidates */
    val valueWeights: DoubleArray? = null,
    /** with <= this many free cells, pick the eat by Monte-Carlo rollouts (0 = off) */
    val rolloutFree: Int = 0,
    /** rollouts per candidate */
    val rolloutCount: Int = 3,
    /** endgame episode seed search: simulate this many own-RNG variants per food (0 = off) */
    val episodeSeeds: Int = 0,
    /** continuation rollouts per episode variant (variance reduction of its value) */
    val episodeRollouts: Int = 1,
    /** run episode search only when freeCells <= this (0 = at the endgame boundary) */
    val episodeFree: Int = 0,
    /** ONNX value net path: replaces continuation rollouts in the episode search */
    val valueNetPath: String? = null,
    /** the net also picks the stall-lap shape (instead of a random bias) */
    val valueStall: Boolean = false,
    /** endgame guard/ranking by repair need R(S) instead of undigestible count */
    val guardRepair: Boolean = false,
    /** endgame ranking with isolated-hole count first (AUC 0.85 vs solved/doomed) */
    val rankHoles: Boolean = false,
    /** hard free-space contiguity guard from this many free cells down (0 = off) */
    val bandFree: Int = 0,
    /** lane-biased (serpentine) path reconstruction */
    val laneBias: Boolean = false,
    /** Sorting-stall: pick the stall shape minimizing free-space fragmentation
     *  at walk end — the "sort holes to one side while the field is plastic" bet. */
    val sortStall: Boolean = false,
    /** Rank midgame eat-walks by post-eat free-space fragmentation (holes are born at eats). */
    val sortEats: Boolean = false,
    /** Exact endgame solver: engage when free cells <= solverFree (0 = off). */
    val solverFree: Int = 0,
    val solverBudget: Int = 150_000,
)

class SafeGreedyStrategy(
    private val timeAware: Boolean,
    private val margin: Int = 0,
    private val hugging: Boolean = false,
    private val guardHoles: Boolean = false,
    private val random: kotlin.random.Random? = null,
    private val sweepEndgame: Boolean = false,
    private val knobs: SafeGreedyKnobs = SafeGreedyKnobs(),
) : Strategy {

    private enum class Commitment { FOOD, ESCAPE, STALL }

    private class Candidate(
        val path: IntArray,
        val postBody: IntArray,
        val escapePlan: IntArray?,
        val staticSafe: Boolean,
        val components: Int,
        val deadCells: Int,
        val undigestible: Int,
        val repair: Double = 0.0,
    ) {
        val safe get() = staticSafe || escapePlan != null
    }

    private var search: BoardSearch? = null
    private var committedPath: IntArray? = null
    private var committedPos = 0
    private var committedKind = Commitment.FOOD
    private var pendingEscape: IntArray? = null

    var desperationEats = 0
        private set
    var timedCommits = 0
        private set
    var escapeChains = 0
        private set
    var midwalkInvalidations = 0
        private set
    var huntCommits = 0
        private set
    var shaperCommits = 0
        private set
    var minimaxFallbacks = 0
        private set
    var episodeCommits = 0
        private set

    /** Data-collection hook: features of the accepted post-eat loop in the endgame. */
    var eatObserver: ((features: DoubleArray) -> Unit)? = null

    /** Data-collection hook: raw post-eat body (head-first cell indices) in the endgame. */
    var stateObserver: ((postBody: IntArray) -> Unit)? = null

    /** Data-collection hook: post-eat body at fill thresholds (decidedness studies). */
    var bucketObserver: ((postBody: IntArray) -> Unit)? = null
    private var nextBucket = 0
    private val buckets = intArrayOf(70, 80, 85, 90, 93, 95, 97, 99)

    private var endgameSolver: org.grakovne.snake.agent.strategy.search.EndgameSolver? = null
    private var lastSolvedFood: Position? = null
    private var nextSolveAttempt = 0
    var solverCommits = 0
        private set

    private var huntExhausted = false
    private var lastHuntStep = -1000
    private var lastFood: Position? = null
    private var cachedStallBias = 0
    private var lastBiasEvalStep = -1000

    override fun nextMove(game: GameView): Direction {
        val board = search?.takeIf { it.width == game.width && it.height == game.height }
            ?: BoardSearch(game.width, game.height).also { search = it }
        board.load(game)

        val choice = choose(game, board)
        return if (game.isSafeStep(choice)) choice else fallback(game, board, choice)
    }

    private fun choose(game: GameView, board: BoardSearch): Direction {
        if (game.food != lastFood) {
            if (lastFood != null) {
                // the tick right after ANY eat (episode commits included): the current
                // body IS the post-eat state — the one true place to observe it
                val area = game.width * game.height
                if (area - game.score <= maxOf(8, area / knobs.endgameDivisor)) {
                    stateObserver?.let { it(bodyIndices(game, board)) }
                }
            }
            lastFood = game.food
            huntExhausted = false

            // Episode seed search: the outcome variance lives in the bot's own stochastic
            // stall choices (measured 885..900 across RNGs on a fixed game seed). The
            // engine is deterministic and no spawn happens until the eat, so a simulated
            // episode replays exactly — simulate a few RNG variants, evaluate each by
            // playing its continuation to the end, and commit the best episode verbatim.
            if (knobs.episodeSeeds > 0 && random != null) {
                val area = game.width * game.height
                val boundary = if (knobs.episodeFree > 0) {
                    knobs.episodeFree
                } else {
                    maxOf(8, area / knobs.endgameDivisor)
                }
                if (area - game.score <= boundary) {
                    episodePlan(game, board)?.let { plan ->
                        episodeCommits++
                        pendingEscape = null
                        return commit(board, plan, Commitment.FOOD)
                    }
                }
            }
        }

        // Timed commitments (food walks, escapes) are followed unconditionally — their
        // correctness depends on exact timing. A stall commitment yields to any accepted
        // food plan below, so acceptance still re-runs every tick while stalling.
        if (committedKind != Commitment.STALL) {
            commitStep(game, board)?.let { return it }
        }

        val area = game.width * game.height
        val freeCells = area - game.score
        val endgame = freeCells <= maxOf(8, area / knobs.endgameDivisor)

        // Exact endgame search: solve the remaining game instead of guarding it.
        // One solve per spawn; a failed attempt (busted budget or proven loss)
        // retries after roughly a lap — a static window may have opened.
        if (knobs.solverFree > 0 && freeCells <= knobs.solverFree &&
            game.food != lastSolvedFood && game.steps >= nextSolveAttempt
        ) {
            val solver = endgameSolver ?: org.grakovne.snake.agent.strategy.search.EndgameSolver(
                width = game.width,
                height = game.height,
                starvationLimit = game.starvationLimit,
                nodeBudget = knobs.solverBudget,
            ).also { endgameSolver = it }
            val solved = solver.solve(game.snake.toList(), game.food)
            // Commit only certified wins: value 1.0 means every future spawn branch
            // reaches the target within the searched model (portfolio walks plus
            // reshaping stall laps). Anything less is a truncated view — the
            // ordinary machinery plays uncertified states better.
            if (solved != null && solved.value >= 0.999) {
                org.grakovne.snake.agent.strategy.search.EndgameSolver.certified.incrementAndGet()
                solverCommits++
                pendingEscape = null
                if (solved.isStall) {
                    // play the reshaping lap, then re-solve for the same food
                    nextSolveAttempt = game.steps + solved.walk.size
                    return commit(board, solved.walk, Commitment.ESCAPE)
                }
                lastSolvedFood = game.food
                return commit(board, solved.walk, Commitment.FOOD)
            }
            nextSolveAttempt = game.steps + maxOf(64, game.score)
        }

        val candidates = buildCandidates(game, board, endgame)
        if (candidates.isNotEmpty()) {
            // Eating the last free cell wins on the spot: no exit needed.
            candidates.firstOrNull { it.postBody.size == area }?.let {
                pendingEscape = null
                return commit(board, it.path, Commitment.FOOD)
            }

            val shortest = candidates.minOf { it.path.size }
            val remainingBudget = game.starvationLimit - game.stepsSinceFood
            // The guard-free phase must span at least one full circulation lap (~score
            // ticks): static windows onto hole food open once per lap, and a shorter
            // desperation tail mostly misses the phase and starves at full budget.
            val desperate = remainingBudget <
                shortest + game.score + knobs.desperationMargin * (game.width + game.height)
            val lastResort = remainingBudget < shortest + 8 ||
                (freeCells <= 2 && game.stepsSinceFood > game.starvationLimit * knobs.patiencePercent / 100)

            val deadNow = board.deadFreeCells()
            val undigestibleNow = if (endgame) board.undigestibleHolesNow(knobs.digestSlack) else 0
            val repairNow = if (endgame && knobs.guardRepair) {
                board.repairNeed(bodyIndices(game, board))
            } else 0.0
            // Fragmentation ranking only matters where the trajectory loop is rigid; in the
            // open midgame it just displaces the better-shaped hugging path. With learned
            // value weights the endgame ranking is by predicted outcome instead.
            val weights = knobs.valueWeights
            val ranked = if (endgame && knobs.rankHoles) {
                candidates.sortedWith(
                    compareBy({ it.deadCells }, { it.components }, { it.undigestible }, { it.path.size })
                )
            } else if (endgame && knobs.guardRepair) {
                candidates.sortedWith(
                    compareBy({ it.repair }, { it.undigestible }, { it.components }, { it.path.size })
                )
            } else if (endgame && weights != null) {
                val scratch = DoubleArray(BoardSearch.FEATURES)
                candidates.sortedByDescending { candidate ->
                    board.loopFeatures(candidate.postBody, scratch)
                    var value = 0.0
                    for (i in scratch.indices) value += weights[i] * scratch[i]
                    value
                }
            } else if (endgame) {
                candidates.sortedWith(
                    compareBy({ it.undigestible }, { it.components }, { it.deadCells }, { it.path.size })
                )
            } else if (knobs.sortEats) {
                candidates.sortedWith(compareBy({ it.components }, { it.path.size }))
            } else {
                candidates
            }

            val inBand = knobs.bandFree > 0 && freeCells <= knobs.bandFree
            fun guarded(candidate: Candidate): Boolean =
                !guardHoles || desperate ||
                    if (inBand) {
                        // hard contiguity: free space must stay in one piece
                        candidate.components <= maxOf(1, board.freeComponents())
                    } else if (endgame && knobs.rankHoles) {
                        candidate.deadCells <= deadNow
                    } else if (endgame && knobs.guardRepair) {
                        candidate.repair <= repairNow + 1e-9
                    } else if (endgame) {
                        !knobs.guardUndigestible || candidate.undigestible <= undigestibleNow
                    } else {
                        !knobs.guardDeadCells || candidate.deadCells <= deadNow
                    }

            // Statically-safe walks first; a timed walk (escape-verified) only when no
            // static candidate survives — timed eats rewire the loop more aggressively
            // and must not displace static ones in the ranking.
            var accepted = ranked.firstOrNull { it.staticSafe && guarded(it) }
                ?: ranked.firstOrNull { it.safe && guarded(it) }

            // Monte-Carlo eat selection at the freeze point: with few cells left a full
            // rollout to the end of the game is cheap, so the candidate is picked by the
            // expected final score over real-engine simulations with random future spawns
            // — the only evaluator that sees the compounded long-horizon consequences.
            if (accepted != null && endgame && knobs.rolloutFree > 0 &&
                freeCells <= knobs.rolloutFree
            ) {
                val pool = ranked.filter { it.staticSafe && guarded(it) }.take(5)
                if (pool.size > 1) {
                    val best = pool.maxBy { rolloutValue(game, board, it) }
                    if (best !== accepted) minimaxFallbacks++
                    accepted = best
                }
            }

            // Every candidate is guard-rejected: directed repair. Beam search over detour
            // insertions reshapes the walk until no undigestible hole remains — detours
            // shift the relative vacate phases of hole walls and swap which tail cells
            // get dropped, which is exactly the material misalignments are made of.
            if (accepted == null && endgame && knobs.shaper &&
                (game.stepsSinceFood <= 8 || game.stepsSinceFood % 4 == 0)
            ) {
                accepted = shapeWalk(game, board, ranked, undigestibleNow)
                if (accepted != null) shaperCommits++
            }

            if (accepted != null) {
                if (!accepted.staticSafe) timedCommits++
                eatObserver?.takeIf { endgame }?.let { observer ->
                    val features = DoubleArray(BoardSearch.FEATURES)
                    board.loopFeatures(accepted.postBody, features)
                    observer(features)
                }
                bucketObserver?.let { observer ->
                    val fillPct = 100 * accepted.postBody.size / area
                    while (nextBucket < buckets.size && fillPct >= buckets[nextBucket]) {
                        observer(accepted.postBody.copyOf())
                        nextBucket++
                    }
                }
                pendingEscape = if (accepted.staticSafe) null else accepted.escapePlan
                return commit(board, accepted.path, Commitment.FOOD)
            }

            if (lastResort) {
                // A verified hunt plan (even a degrading one) beats a blind unsafe eat.
                board.bestHuntPlan(bodyIndices(game, board))?.let { plan ->
                    if (plan.path.size > 1) {
                        huntCommits++
                        pendingEscape = plan.escape
                        return commit(board, plan.path, Commitment.FOOD)
                    }
                }
                desperationEats++
                val best = ranked.firstOrNull { it.safe } ?: ranked.first()
                pendingEscape = if (best.staticSafe) null else best.escapePlan
                return commit(board, best.path, Commitment.FOOD)
            }
        }

        // Phase-rotation hunts proved mathematically near-useless as a first-line tool
        // (the head moves with the schedule, so relative phases are topologically frozen);
        // they remain only as last-resort eats. This branch covers the case with no food
        // candidates at all (food statically unreachable): with the budget almost gone,
        // a verified hunt plan or even an unverified timed walk beats starving in place.
        if (endgame && game.starvationLimit - game.stepsSinceFood < 2 * (game.width + game.height)) {
            board.bestHuntPlan(bodyIndices(game, board))?.let { plan ->
                if (plan.path.size > 1) {
                    huntCommits++
                    pendingEscape = plan.escape
                    return commit(board, plan.path, Commitment.FOOD)
                }
            }
            val bite = board.shortestPathFromHead(
                target = board.foodIndex(),
                timeAware = true,
                margin = 0,
            )
            if (bite != null && bite.size > 1) {
                desperationEats++
                pendingEscape = null
                return commit(board, bite, Commitment.FOOD)
            }
        }

        // No acceptable food plan: stall. Two regimes with opposite needs:
        // - midgame (open space): commit to the stall walk — chains of first steps from
        //   different plans do not compose into a valid path, and that replanning drift
        //   is what walks into dead-end pockets;
        // - endgame (rigid corridor): replan chaotically per tick — there is nothing to
        //   drift into, and reshaping the trajectory explores vacate schedules.
        if (knobs.stallCommitMidgame && !endgame && committedKind == Commitment.STALL) {
            commitStep(game, board)?.let { return it }
        }

        if (knobs.sortStall && !endgame) {
            // Sorting as a filter, not a dictator: keep the chaos coin, but flip it
            // only among the stall shapes with minimal free-space fragmentation.
            val body = bodyIndices(game, board)
            var bestFrag = Int.MAX_VALUE
            val argmin = ArrayList<IntArray>(4)
            for (bias in 0 until 4) {
                val p = board.longestPathToTail(
                    directionBias = bias,
                    avoidAroundFood = knobs.avoidAroundFood,
                ) ?: continue
                if (p.size < 2) continue
                val frag = board.freeComponentsFor(board.bodyAfterWalk(body, p))
                if (frag < bestFrag) {
                    bestFrag = frag
                    argmin.clear()
                }
                if (frag == bestFrag) argmin.add(p)
            }
            if (argmin.isNotEmpty()) {
                val pick = argmin[random?.nextInt(argmin.size) ?: 0]
                return holeGuarded(game, board, step(board, pick[0], pick[1]))
            }
        }

        val chaosHere = knobs.chaosStall &&
            (if (endgame) knobs.chaosEndgame else knobs.chaosMidgame)
        // Value-guided stalling: the outcome variance lives in these lap-shape coin
        // flips (measured 885..900 on a fixed seed) — let the net judge the four
        // shapes by the state each full lap would leave, instead of rolling a die.
        val stallBias = if (knobs.valueStall && knobs.valueNetPath != null && endgame) {
            if (game.steps - lastBiasEvalStep >= 16) {
                lastBiasEvalStep = game.steps
                cachedStallBias = bestStallBias(game, board) ?: (random?.nextInt(4) ?: 0)
            }
            cachedStallBias
        } else if (chaosHere) {
            random?.nextInt(4) ?: 0
        } else {
            0
        }
        val stallPath = board.longestPathToTail(
            directionBias = stallBias,
            avoidAroundFood = knobs.avoidAroundFood,
        )
        if (stallPath != null && stallPath.size > 1) {
            val planned = step(board, stallPath[0], stallPath[1])
            val choice = holeGuarded(game, board, planned)
            if (knobs.stallCommitMidgame && !endgame && choice == planned) {
                return commit(board, stallPath, Commitment.STALL)
            }
            return choice
        }

        val escapePath = board.escapePlanFor(bodyIndices(game, board), timeAware = true)
        if (escapePath != null && escapePath.size > 1) {
            pendingEscape = null
            return commit(board, escapePath, Commitment.ESCAPE)
        }

        return fallback(game, board, game.heading)
    }

    /**
     * Simulates [SafeGreedyKnobs.episodeSeeds] own-RNG variants of the current food
     * episode; returns the head-cell walk (exact replay) of the variant whose full-game
     * continuation scored best, or null when every variant died before eating.
     */
    private fun episodePlan(game: GameView, board: BoardSearch): IntArray? {
        val body = game.snake.toList()
        var bestValue = Double.NEGATIVE_INFINITY
        var bestWalk: IntArray? = null
        // Common random numbers: identical continuation seeds across episode variants —
        // future-spawn noise correlates and cancels in the comparison.
        val continuationSeeds = LongArray(knobs.episodeRollouts) { random!!.nextLong() }

        repeat(knobs.episodeSeeds) {
            val seed = random!!.nextLong()
            val sim = org.grakovne.snake.agent.core.SnakeGame(
                org.grakovne.snake.agent.core.GameConfig(
                    width = game.width,
                    height = game.height,
                    seed = seed,
                    maxStepsWithoutFood = game.starvationLimit,
                ),
                initialBody = body,
                initialFood = game.food,
            )
            val policy = SafeGreedyStrategy(
                timeAware = false,
                guardHoles = true,
                random = kotlin.random.Random(seed),
                knobs = knobs.copy(episodeSeeds = 0, rolloutFree = 0, valueWeights = null),
            )
            // exact part: play the episode until the eat (no spawn happens before it)
            val walk = ArrayList<Int>(256)
            walk.add(board.index(sim.head))
            val startScore = sim.score
            while (sim.status == org.grakovne.snake.agent.core.GameStatus.RUNNING &&
                sim.score == startScore
            ) {
                sim.step(policy.nextMove(sim))
                walk.add(board.index(sim.head))
            }
            if (sim.score == startScore) return@repeat   // died without eating
            // value the post-eat state: the trained net in one forward pass, or averaged
            // paired continuation rollouts when no net is configured
            val value = if (sim.status != org.grakovne.snake.agent.core.GameStatus.RUNNING) {
                sim.score.toDouble()
            } else if (knobs.valueNetPath != null) {
                val net = org.grakovne.snake.agent.strategy.value.ValueNet.sharedFor(
                    knobs.valueNetPath, game.width, game.height,
                )
                val postBody = IntArray(sim.snake.size) { i ->
                    sim.snake[i].y * game.width + sim.snake[i].x
                }
                game.width * game.height - net.predictDeficit(postBody)
            } else {
                rolloutMean(game, sim.snake.toList(), continuationSeeds)
            }
            if (value > bestValue) {
                bestValue = value
                bestWalk = walk.toIntArray()
            }
        }
        return bestWalk?.takeIf { it.size > 1 }
    }

    /** Mean final score over paired continuations from a state. */
    private fun rolloutMean(game: GameView, body: List<Position>, seeds: LongArray): Double {
        var total = 0.0
        for (seed in seeds) {
            val rollout = org.grakovne.snake.agent.core.SnakeGame(
                org.grakovne.snake.agent.core.GameConfig(
                    width = game.width,
                    height = game.height,
                    seed = seed,
                    maxStepsWithoutFood = game.starvationLimit,
                ),
                initialBody = body,
            )
            val policy = SafeGreedyStrategy(
                timeAware = false,
                guardHoles = true,
                random = kotlin.random.Random(seed),
                knobs = knobs.copy(episodeSeeds = 0, rolloutFree = 0, valueWeights = null),
            )
            while (rollout.status == org.grakovne.snake.agent.core.GameStatus.RUNNING) {
                rollout.step(policy.nextMove(rollout))
            }
            total += rollout.score
        }
        return total / seeds.size
    }

    /** Picks the stall bias whose full-lap walk leaves the lowest predicted deficit. */
    private fun bestStallBias(game: GameView, board: BoardSearch): Int? {
        val netPath = knobs.valueNetPath ?: return null
        val net = org.grakovne.snake.agent.strategy.value.ValueNet.sharedFor(
            netPath, game.width, game.height,
        )
        val body = bodyIndices(game, board)
        var best: Int? = null
        var bestDeficit = Double.MAX_VALUE
        for (bias in 0 until 4) {
            val path = board.longestPathToTail(
                directionBias = bias, avoidAroundFood = knobs.avoidAroundFood,
            ) ?: continue
            if (path.size < 2) continue
            val after = board.bodyAfterWalk(body, path)
            val deficit = net.predictDeficit(after)
            if (deficit < bestDeficit) {
                bestDeficit = deficit
                best = bias
            }
        }
        return best
    }

    /** Mean final score of real-engine rollouts from the candidate's post-eat state. */
    private fun rolloutValue(game: GameView, board: BoardSearch, candidate: Candidate): Double {
        val body = candidate.postBody.map { Position(it % board.width, it / board.width) }
        var total = 0.0
        repeat(knobs.rolloutCount) {
            val seed = random?.nextLong() ?: it.toLong()
            val rollout = org.grakovne.snake.agent.core.SnakeGame(
                org.grakovne.snake.agent.core.GameConfig(
                    width = game.width,
                    height = game.height,
                    seed = seed,
                    maxStepsWithoutFood = game.starvationLimit,
                ),
                initialBody = body,
            )
            val policy = SafeGreedyStrategy(
                timeAware = false,
                guardHoles = true,
                random = kotlin.random.Random(seed),
                knobs = knobs.copy(rolloutFree = 0, valueWeights = null),
            )
            while (rollout.status == org.grakovne.snake.agent.core.GameStatus.RUNNING) {
                rollout.step(policy.nextMove(rollout))
            }
            total += rollout.score
        }
        return total / knobs.rolloutCount
    }

    /**
     * Directed walk repair: beam search over single-detour insertions, minimizing the
     * number of undigestible holes after eating. Safety is verified only on finalists.
     */
    private fun shapeWalk(
        game: GameView,
        board: BoardSearch,
        seeds: List<Candidate>,
        undigestibleNow: Int,
    ): Candidate? {
        class Node(val walk: IntArray, val undigestible: Int)

        var budget = 160
        val seen = HashSet<Int>()
        var beam = seeds.take(3).map { Node(it.path, it.undigestible) }
        if (beam.isEmpty()) return null

        repeat(8) {
            val expansions = ArrayList<Node>()
            for (node in beam) {
                if (budget <= 0 || node.undigestible == 0) continue
                val holes = board.undigestibleHoleCells(board.bodyAfterEating(game.snake, node.walk), knobs.digestSlack)
                board.detourVariants(node.walk, holes, limit = 16) { variant ->
                    if (budget-- > 0 && seen.add(variant.contentHashCode())) {
                        expansions.add(
                            Node(
                                variant,
                                board.undigestibleHoles(board.bodyAfterEating(game.snake, variant), knobs.digestSlack),
                            )
                        )
                    }
                }
            }
            if (expansions.isEmpty()) return@repeat
            beam = (beam + expansions).sortedBy { it.undigestible }.take(3)
        }

        for (node in beam.sortedBy { it.undigestible }) {
            if (node.undigestible > undigestibleNow) break
            val candidate = evaluate(game, board, node.walk, endgame = true) ?: continue
            if (candidate.safe) return candidate
        }
        return null
    }

    private fun buildCandidates(game: GameView, board: BoardSearch, endgame: Boolean): List<Candidate> {
        val paths = ArrayList<IntArray>(6)
        board.shortestPathFromHead(
            target = board.foodIndex(),
            timeAware = timeAware,
            margin = margin,
            hugging = knobs.hugging,
            laneBias = knobs.laneBias,
        )?.let { paths.add(it) }
        if (endgame) {
            if (knobs.hugging) {
                board.shortestPathFromHead(
                    target = board.foodIndex(),
                    timeAware = timeAware,
                    margin = margin,
                    hugging = false,
                )?.let { paths.add(it) }
            }
            // Every intermediate extension shape is a candidate: the walk that leaves the
            // free space digestible is usually neither the shortest nor the maximal sweep.
            // Several randomized runs cover genuinely different orderings; during long
            // stalls the wide search runs on a cadence to keep the tick affordable.
            val wide = game.stepsSinceFood <= 16 || game.stepsSinceFood % 4 == 0
            board.foodPathSnapshots(rng = null, limit = 24) { paths.add(it) }
            if (wide) {
                repeat(5) {
                    board.foodPathSnapshots(rng = random, limit = 20) { paths.add(it) }
                }
            }
            // In the rigid zero-gap endgame loop there are NO statically free cells: the
            // only route to food is the corridor of vacating tail cells. The timed path is
            // the one candidate that can see it; acceptance still demands a statically-safe
            // or escape-verified post-eat state.
            // The timed corridor candidate: harmful as a first-line option (measured), but
            // it is the only route into holes and pockets a static path never reaches —
            // enable it as a rescue once a hunt has burned a chunk of the budget.
            val rescue = knobs.timedRescuePercent > 0 &&
                game.stepsSinceFood > game.starvationLimit * knobs.timedRescuePercent / 100
            if (!timeAware && (knobs.timedCandidate || rescue)) {
                board.shortestPathFromHead(
                    target = board.foodIndex(),
                    timeAware = true,
                    margin = 0,
                )?.let { paths.add(it) }
            }
        }

        return paths.mapNotNull { path -> evaluate(game, board, path, endgame) }
    }

    private fun evaluate(game: GameView, board: BoardSearch, path: IntArray, endgame: Boolean): Candidate? {
        if (path.size < 2) return null
        val postBody = board.bodyAfterEating(game.snake, path)
        val staticSafe = board.tailReachableFor(postBody, timeAware = false)
        var escapePlan: IntArray? = null
        if (!staticSafe && endgame) {
            // Corridor-only escapes first (immune to food respawn); a free-crossing escape
            // as a second chance — needed to leave multi-cell pockets after eating inside.
            val plan = board.escapePlanFor(postBody, timeAware = true, avoidFree = true)
                ?.takeIf { it.size > 1 }
                ?: board.escapePlanFor(postBody, timeAware = true, avoidFree = false)
            if (plan != null && plan.size > 1 &&
                board.tailReachableFor(board.bodyAfterWalk(postBody, plan), timeAware = false)
            ) {
                escapePlan = plan
            }
        }
        return Candidate(
            path = path,
            postBody = postBody,
            escapePlan = escapePlan,
            staticSafe = staticSafe,
            components = board.freeComponentsFor(postBody),
            deadCells = board.deadFreeCellsFor(postBody),
            undigestible = if (endgame) board.undigestibleHoles(postBody, knobs.digestSlack) else 0,
            repair = if (endgame && knobs.guardRepair) board.repairNeed(postBody) else 0.0,
        )
    }

    /**
     * Swaps a stall move that would strand a dead free cell for one that does not — but
     * only for an alternative that itself keeps the tail statically reachable; giving up
     * the tail invariant is worse than stranding a hole.
     */
    private fun holeGuarded(game: GameView, board: BoardSearch, choice: Direction): Direction {
        if (!guardHoles) return choice
        if (!board.moveCreatesDeadCell(board.index(game.head + choice))) return choice
        val body = bodyIndices(game, board)
        return game.legalMoves()
            .filter { it != choice && !board.moveCreatesDeadCell(board.index(game.head + it)) }
            // never step onto the food outside an accepted plan: an accidental bite
            // bypasses every safety check
            .filter { board.index(game.head + it) != board.foodIndex() }
            .filter {
                val walk = intArrayOf(board.headIndex(), board.index(game.head + it))
                board.tailReachableFor(board.bodyAfterWalk(body, walk), timeAware = false)
            }
            .maxByOrNull { board.floodSizeFrom(board.index(game.head + it)) }
            ?: choice
    }

    private fun commit(board: BoardSearch, path: IntArray, kind: Commitment): Direction {
        committedPath = path
        committedPos = 1
        committedKind = kind
        return step(board, path[0], path[1])
    }

    /** Follows a previously committed plan while its vacate predictions keep coming true. */
    private fun commitStep(game: GameView, board: BoardSearch): Direction? {
        var path = committedPath ?: return null

        // The food plan just completed (head is on its last cell): chain into the escape.
        if (committedKind == Commitment.FOOD &&
            committedPos >= path.size - 1 &&
            board.headIndex() == path.last()
        ) {
            path = pendingEscape ?: return null.also { committedPath = null }
            escapeChains++
            committedPath = path
            committedPos = 0
            committedKind = Commitment.ESCAPE
            pendingEscape = null
        }

        val next = if (committedPos < path.size - 1) path[committedPos + 1] else -1
        val valid = next != -1 &&
            path[committedPos] == board.headIndex() &&
            (committedKind != Commitment.FOOD || board.foodIndex() == path.last()) &&
            // an unplanned bite on a stall/escape route bypasses every safety check
            (committedKind == Commitment.FOOD || next != board.foodIndex()) &&
            isCurrentlyEnterable(game, board, next)
        if (!valid) {
            if (committedPos < path.size - 1) midwalkInvalidations++
            committedPath = null
            return null
        }
        val direction = step(board, path[committedPos], next)
        committedPos++
        return direction
    }

    private fun bodyIndices(game: GameView, board: BoardSearch): IntArray {
        val body = game.snake
        return IntArray(body.size) { board.index(body[it]) }
    }

    private fun isCurrentlyEnterable(game: GameView, board: BoardSearch, cell: Int): Boolean {
        val position = Position(cell % board.width, cell / board.width)
        return game.isFree(position) || position == game.snake.last()
    }

    private fun fallback(game: GameView, board: BoardSearch, previousChoice: Direction): Direction {
        committedPath = null
        val legal = game.legalMoves()
        if (legal.isEmpty()) return previousChoice
        // Survival first: prefer moves after which the tail stays statically reachable,
        // then moves that do not bite the food by accident (an unplanned eat bypasses
        // every safety check), then the largest reachable area.
        val body = bodyIndices(game, board)
        return legal.maxWith(
            compareBy(
                { move ->
                    val target = board.index(game.head + move)
                    val post = if (target == board.foodIndex()) {
                        board.bodyAfterEating(game.snake, intArrayOf(board.headIndex(), target))
                    } else {
                        board.bodyAfterWalk(body, intArrayOf(board.headIndex(), target))
                    }
                    if (board.tailReachableFor(post, timeAware = false)) 1 else 0
                },
                { move -> if (board.index(game.head + move) != board.foodIndex()) 1 else 0 },
                { move -> board.floodSizeFrom(board.index(game.head + move)) },
            )
        )
    }

    private fun step(board: BoardSearch, from: Int, to: Int): Direction =
        when (board.neighborTowards(from, to)) {
            -board.width -> Direction.UP
            board.width -> Direction.DOWN
            -1 -> Direction.LEFT
            else -> Direction.RIGHT
        }
}
