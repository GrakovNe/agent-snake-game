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

    private var huntExhausted = false
    private var lastHuntStep = -1000
    private var lastFood: Position? = null

    override fun nextMove(game: GameView): Direction {
        val board = search?.takeIf { it.width == game.width && it.height == game.height }
            ?: BoardSearch(game.width, game.height).also { search = it }
        board.load(game)

        val choice = choose(game, board)
        return if (game.isSafeStep(choice)) choice else fallback(game, board, choice)
    }

    private fun choose(game: GameView, board: BoardSearch): Direction {
        if (game.food != lastFood) {
            lastFood = game.food
            huntExhausted = false
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
            val undigestibleNow = if (endgame) board.undigestibleHolesNow() else 0
            // Fragmentation ranking only matters where the trajectory loop is rigid; in the
            // open midgame it just displaces the better-shaped hugging path.
            val ranked = if (endgame) {
                candidates.sortedWith(
                    compareBy({ it.undigestible }, { it.components }, { it.deadCells }, { it.path.size })
                )
            } else {
                candidates
            }

            fun guarded(candidate: Candidate): Boolean =
                !guardHoles || desperate ||
                    if (endgame) {
                        !knobs.guardUndigestible || candidate.undigestible <= undigestibleNow
                    } else {
                        !knobs.guardDeadCells || candidate.deadCells <= deadNow
                    }

            // Statically-safe walks first; a timed walk (escape-verified) only when no
            // static candidate survives — timed eats rewire the loop more aggressively
            // and must not displace static ones in the ranking.
            val accepted = ranked.firstOrNull { it.staticSafe && guarded(it) }
                ?: ranked.firstOrNull { it.safe && guarded(it) }
            if (accepted != null) {
                if (!accepted.staticSafe) timedCommits++
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

        val stallPath = board.longestPathToTail(
            directionBias = if (knobs.chaosStall) random?.nextInt(4) ?: 0 else 0,
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

    private fun buildCandidates(game: GameView, board: BoardSearch, endgame: Boolean): List<Candidate> {
        val paths = ArrayList<IntArray>(6)
        board.shortestPathFromHead(
            target = board.foodIndex(),
            timeAware = timeAware,
            margin = margin,
            hugging = knobs.hugging,
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
            undigestible = if (endgame) board.undigestibleHoles(postBody) else 0,
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
