package org.grakovne.snake.agent.core

/**
 * Read-only view of a running game, the only thing a [org.grakovne.snake.agent.strategy.Strategy] sees.
 * [snake] is ordered head first; the list is a live view, copy it if you need a snapshot.
 */
interface GameView {
    val width: Int
    val height: Int

    /** Head first, tail last. */
    val snake: List<Position>
    val food: Position
    val heading: Direction
    val score: Int
    val steps: Int
    val status: GameStatus

    val head: Position get() = snake.first()

    fun contains(position: Position): Boolean =
        position.x in 0 until width && position.y in 0 until height

    fun isOccupied(position: Position): Boolean

    fun isFree(position: Position): Boolean = contains(position) && !isOccupied(position)

    /**
     * True when stepping there does not kill immediately. The tail cell counts as free
     * because the tail vacates it in the same tick (unless that step eats, but food is
     * never on an occupied cell).
     */
    fun isSafeStep(direction: Direction): Boolean {
        val target = head + direction
        if (!contains(target)) return false
        if (!isOccupied(target)) return true
        return target == snake.last() && target != food
    }

    fun legalMoves(): List<Direction> = Direction.entries.filter { isSafeStep(it) }
}
