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
class SafeGreedyStrategy(
    private val timeAware: Boolean,
    private val margin: Int = 0,
    private val hugging: Boolean = false,
    private val guardHoles: Boolean = false,
    private val random: kotlin.random.Random? = null,
    private val sweepEndgame: Boolean = false,
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

    override fun nextMove(game: GameView): Direction {
        val board = search?.takeIf { it.width == game.width && it.height == game.height }
            ?: BoardSearch(game.width, game.height).also { search = it }
        board.load(game)

        val choice = choose(game, board)
        return if (game.isSafeStep(choice)) choice else fallback(game, board, choice)
    }

    private fun choose(game: GameView, board: BoardSearch): Direction {
        // Timed commitments (food walks, escapes) are followed unconditionally — their
        // correctness depends on exact timing. A stall commitment yields to any accepted
        // food plan below, so acceptance still re-runs every tick while stalling.
        if (committedKind != Commitment.STALL) {
            commitStep(game, board)?.let { return it }
        }

        val area = game.width * game.height
        val freeCells = area - game.score
        val endgame = freeCells <= maxOf(8, area / 20)

        val candidates = buildCandidates(game, board, endgame)
        if (candidates.isNotEmpty()) {
            // Eating the last free cell wins on the spot: no exit needed.
            candidates.firstOrNull { it.postBody.size == area }?.let {
                pendingEscape = null
                return commit(board, it.path, Commitment.FOOD)
            }

            val shortest = candidates.minOf { it.path.size }
            val remainingBudget = game.starvationLimit - game.stepsSinceFood
            val desperate = remainingBudget < shortest + 2 * (game.width + game.height)
            val lastResort = remainingBudget < shortest + 8 ||
                (freeCells <= 2 && game.stepsSinceFood > game.starvationLimit / 2)

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
                        // Rigid-loop phase: after this eat the snake settles into the
                        // postBody loop; the number of future food spawns that would be
                        // inedible under that loop's schedule must not grow.
                        candidate.undigestible <= undigestibleNow
                    } else {
                        candidate.deadCells <= deadNow
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
                desperationEats++
                val best = ranked.firstOrNull { it.safe } ?: ranked.first()
                pendingEscape = if (best.staticSafe) null else best.escapePlan
                return commit(board, best.path, Commitment.FOOD)
            }
        }

        // No acceptable food plan: stall. Two regimes with opposite needs:
        // - midgame (open space): commit to the stall walk — chains of first steps from
        //   different plans do not compose into a valid path, and that replanning drift
        //   is what walks into dead-end pockets;
        // - endgame (rigid corridor): replan chaotically per tick — there is nothing to
        //   drift into, and reshaping the trajectory explores vacate schedules.
        if (!endgame && committedKind == Commitment.STALL) {
            commitStep(game, board)?.let { return it }
        }

        val stallPath = board.longestPathToTail(
            directionBias = random?.nextInt(4) ?: 0,
            avoidAroundFood = true,
        )
        if (stallPath != null && stallPath.size > 1) {
            val planned = step(board, stallPath[0], stallPath[1])
            val choice = holeGuarded(game, board, planned)
            if (!endgame && choice == planned) {
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
            hugging = hugging,
        )?.let { paths.add(it) }
        if (endgame) {
            if (hugging) {
                board.shortestPathFromHead(
                    target = board.foodIndex(),
                    timeAware = timeAware,
                    margin = margin,
                    hugging = false,
                )?.let { paths.add(it) }
            }
            if (sweepEndgame) {
                for (bias in 0 until 4) {
                    board.longestPathToFood(directionBias = bias)?.let { paths.add(it) }
                }
            }
            // In the rigid zero-gap endgame loop there are NO statically free cells: the
            // only route to food is the corridor of vacating tail cells. The timed path is
            // the one candidate that can see it; acceptance still demands a statically-safe
            // or escape-verified post-eat state.
            if (!timeAware) {
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
            val plan = board.escapePlanFor(postBody, timeAware = true, avoidFree = true)
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
