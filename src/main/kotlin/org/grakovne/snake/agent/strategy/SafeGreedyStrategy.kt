package org.grakovne.snake.agent.strategy

import org.grakovne.snake.agent.core.Direction
import org.grakovne.snake.agent.core.GameView
import org.grakovne.snake.agent.core.Position
import org.grakovne.snake.agent.strategy.search.BoardSearch

/**
 * Tail-reachability greedy with constructive timed escapes.
 *
 * Move preference order:
 * 1. shortest path to food ([timeAware]: body cells count as passable once they vacate),
 *    accepted if after virtually eating either (a) the tail stays statically reachable, or
 *    (b) a concrete timed escape walk to the tail exists — this is what makes single-cell
 *    holes edible: enter, eat, and leave through a neighbor that vacates on schedule.
 *    Accepted plans are committed and followed verbatim; timings are exact in a
 *    deterministic engine, and mid-corridor replanning is what kills timed plans;
 * 2. stall along the (approximate) longest static path to the own tail — the acceptance
 *    check reruns every tick, so a periodic stall loop scans all timing phases;
 * 3. escape: committed timed walk to the tail;
 * 4. the legal move with the largest reachable area;
 * 5. desperation override: when the starvation budget runs low, take any path to food.
 */
class SafeGreedyStrategy(
    private val timeAware: Boolean,
    private val margin: Int = 0,
    private val hugging: Boolean = false,
    private val guardHoles: Boolean = false,
    private val random: kotlin.random.Random? = null,
) : Strategy {

    private enum class Commitment { FOOD, ESCAPE }

    private var search: BoardSearch? = null
    private var committedPath: IntArray? = null
    private var committedPos = 0
    private var committedKind = Commitment.FOOD
    private var pendingEscape: IntArray? = null

    var desperationEats = 0
        private set


    override fun nextMove(game: GameView): Direction {
        val board = search?.takeIf { it.width == game.width && it.height == game.height }
            ?: BoardSearch(game.width, game.height).also { search = it }
        board.load(game)

        val choice = choose(game, board)
        return if (game.isSafeStep(choice)) choice else fallback(game, board, choice)
    }

    private fun choose(game: GameView, board: BoardSearch): Direction {
        commitStep(game, board)?.let { return it }

        val foodPath = board.shortestPathFromHead(
            target = board.foodIndex(),
            timeAware = timeAware,
            margin = margin,
            hugging = hugging,
        )

        if (foodPath != null) {
            val postBody = board.bodyAfterEating(game.snake, foodPath)
            val remainingBudget = game.starvationLimit - game.stepsSinceFood
            // Graded desperation: first lift the hole guard, only near the very end of the
            // starvation budget accept plans with no static safety at all. With <= 2 free
            // cells there is nothing left to protect — eating is worth it even if it traps.
            val freeCellsNow = game.width * game.height - game.score
            val desperate = remainingBudget < foodPath.size + 2 * (game.width + game.height)
            // With <= 2 free cells left, hunt for a timed window for half the budget, then
            // eat even if it traps: area-1 with certainty beats starving for the full area.
            val lastResort = remainingBudget < foodPath.size + 8 ||
                (freeCellsNow <= 2 && game.stepsSinceFood > game.starvationLimit / 2)

            // Holes are cheaper to avoid than to eat: reject plans that strand a free cell
            // with no free neighbors (unless the board already had it, or time presses).
            val holeSafe = !guardHoles || desperate ||
                board.deadFreeCellsFor(postBody) <= board.deadFreeCells()

            // Eating the last free cell wins on the spot: no exit needed.
            if (postBody.size == game.width * game.height) {
                pendingEscape = null
                return commit(board, foodPath, Commitment.FOOD)
            }

            if (holeSafe && board.tailReachableFor(postBody, timeAware = false)) {
                pendingEscape = null
                return commit(board, foodPath, Commitment.FOOD)
            }

            // No static safety after eating: look for a concrete timed escape walk that
            // itself ends in a statically-safe state. This is what makes single-cell
            // holes edible: enter, eat, leave through a neighbor that vacates on schedule.
            // Endgame-only: timed chains are brittle against food respawning on the walk,
            // and before the board is packed static acceptance is all we need.
            val freeCells = game.width * game.height - game.score
            val endgame = freeCells <= maxOf(8, game.width * game.height / 20)
            val escapePlan = if (endgame) board.escapePlanFor(postBody, timeAware = true) else null
            if (escapePlan != null && escapePlan.size > 1) {
                val postEscape = board.bodyAfterWalk(postBody, escapePlan)
                if (board.tailReachableFor(postEscape, timeAware = false)) {
                    pendingEscape = escapePlan
                    return commit(board, foodPath, Commitment.FOOD)
                }
            }

            if (lastResort) {
                desperationEats++
                pendingEscape = escapePlan
                return commit(board, foodPath, Commitment.FOOD)
            }
        }

        // Chaotic per-tick reshaping of the stall trajectory explores vacate-schedule space
        // fastest and lets timed windows onto stranded holes align (measured better than
        // both a fixed shape and per-lap coherent variation).
        val stallPath = board.longestPathToTail(directionBias = random?.nextInt(4) ?: 0)
        if (stallPath != null && stallPath.size > 1) {
            return holeGuarded(game, board, step(board, stallPath[0], stallPath[1]))
        }

        val escapePath = board.escapePlanFor(bodyIndices(game, board), timeAware = true)
        if (escapePath != null && escapePath.size > 1) {
            pendingEscape = null
            return commit(board, escapePath, Commitment.ESCAPE)
        }

        return fallback(game, board, game.heading)
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
            committedPath = path
            committedPos = 0
            committedKind = Commitment.ESCAPE
            pendingEscape = null
        }

        val next = if (committedPos < path.size - 1) path[committedPos + 1] else -1
        val valid = next != -1 &&
            path[committedPos] == board.headIndex() &&
            (committedKind != Commitment.FOOD || board.foodIndex() == path.last()) &&
            // an unplanned food on an escape route would break the walk's timing
            (committedKind != Commitment.ESCAPE || next != board.foodIndex()) &&
            isCurrentlyEnterable(game, board, next)
        if (!valid) {
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
        return legal.maxBy { board.floodSizeFrom(board.index(game.head + it)) }
    }

    private fun step(board: BoardSearch, from: Int, to: Int): Direction =
        when (board.neighborTowards(from, to)) {
            -board.width -> Direction.UP
            board.width -> Direction.DOWN
            -1 -> Direction.LEFT
            else -> Direction.RIGHT
        }
}
