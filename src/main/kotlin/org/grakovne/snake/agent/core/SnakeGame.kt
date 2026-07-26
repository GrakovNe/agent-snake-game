package org.grakovne.snake.agent.core

import kotlin.random.Random

/**
 * Deterministic snake engine: the snake body is the single source of truth,
 * collisions are O(1) via an occupancy grid, food spawns uniformly on free cells.
 */
class SnakeGame(val config: GameConfig) : GameView {

    override val width = config.width
    override val height = config.height

    private val random = Random(config.seed)
    private val occupied = BooleanArray(width * height)
    private val body = ArrayDeque<Position>(width * height)

    override val snake: List<Position> get() = body
    override val score: Int get() = body.size
    override val head: Position get() = body.first()

    override var food: Position = Position(0, 0)
        private set
    override var heading: Direction = Direction.RIGHT
        private set
    override var status: GameStatus = GameStatus.RUNNING
        private set
    override var steps: Int = 0
        private set

    var deathReason: DeathReason? = null
        private set
    override var stepsSinceFood: Int = 0
        private set
    override val starvationLimit: Int = config.maxStepsWithoutFood

    init {
        val headX = width / 2
        val headY = height / 2
        repeat(config.initialLength) { offset ->
            val segment = Position(headX - offset, headY)
            body.addLast(segment)
            occupied[segment.index()] = true
        }
        food = spawnFood()
    }

    override fun isOccupied(position: Position) = occupied[position.index()]

    fun step(direction: Direction): GameStatus {
        check(status == GameStatus.RUNNING) { "step() called on a finished game" }
        steps++

        val target = head + direction
        if (!contains(target)) return die(DeathReason.HIT_WALL)

        val eats = target == food
        if (occupied[target.index()] && !(target == body.last() && !eats)) {
            return die(DeathReason.HIT_SELF)
        }

        if (!eats) {
            val tail = body.removeLast()
            occupied[tail.index()] = false
        }
        body.addFirst(target)
        occupied[target.index()] = true
        heading = direction

        if (eats) {
            stepsSinceFood = 0
            if (body.size == width * height) {
                status = GameStatus.WON
            } else {
                food = spawnFood()
            }
        } else {
            stepsSinceFood++
            if (stepsSinceFood >= config.maxStepsWithoutFood) return die(DeathReason.STARVED)
        }

        return status
    }

    fun result() = GameResult(score, steps, status, deathReason, config.seed)

    internal fun overrideFood(position: Position) {
        require(isFree(position)) { "food must be on a free cell" }
        food = position
    }

    private fun die(reason: DeathReason): GameStatus {
        status = GameStatus.DEAD
        deathReason = reason
        return status
    }

    private fun spawnFood(): Position {
        val freeCells = width * height - body.size
        var skip = random.nextInt(freeCells)
        for (index in occupied.indices) {
            if (!occupied[index]) {
                if (skip == 0) return Position(index % width, index / width)
                skip--
            }
        }
        error("no free cell to spawn food on")
    }

    private fun Position.index() = y * width + x
}
