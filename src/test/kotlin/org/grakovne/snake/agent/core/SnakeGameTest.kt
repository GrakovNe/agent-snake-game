package org.grakovne.snake.agent.core

import org.grakovne.snake.agent.sim.GameRunner
import org.grakovne.snake.agent.strategy.GreedyStrategy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SnakeGameTest {

    // 8x8, initialLength 4: body [(4,4),(3,4),(2,4),(1,4)], heading RIGHT
    private fun game(seed: Long = 1) =
        SnakeGame(GameConfig(width = 8, height = 8, seed = seed, initialLength = 4))

    @Test
    fun `initial state is consistent`() {
        val game = game()
        assertEquals(4, game.score)
        assertEquals(Position(4, 4), game.head)
        assertEquals(Direction.RIGHT, game.heading)
        assertEquals(GameStatus.RUNNING, game.status)
        assertTrue(game.contains(game.food))
        assertFalse(game.isOccupied(game.food))
    }

    @Test
    fun `food never spawns on the snake`() {
        repeat(200) { seed ->
            val game = game(seed.toLong())
            assertFalse(game.isOccupied(game.food), "seed $seed spawned food on the snake")
        }
    }

    @Test
    fun `walking into a wall kills`() {
        val game = game()
        game.overrideFood(Position(0, 0))
        repeat(3) { assertEquals(GameStatus.RUNNING, game.step(Direction.RIGHT)) }
        assertEquals(GameStatus.DEAD, game.step(Direction.RIGHT))
        assertEquals(DeathReason.HIT_WALL, game.deathReason)
    }

    @Test
    fun `reversing into the neck kills`() {
        val game = game()
        assertEquals(GameStatus.DEAD, game.step(Direction.LEFT))
        assertEquals(DeathReason.HIT_SELF, game.deathReason)
    }

    @Test
    fun `legal moves exclude the neck`() {
        val game = game()
        assertEquals(listOf(Direction.UP, Direction.DOWN, Direction.RIGHT), game.legalMoves())
    }

    @Test
    fun `moving into the vacating tail cell is legal`() {
        val game = game()
        game.overrideFood(Position(0, 0))
        game.step(Direction.UP)
        game.step(Direction.LEFT)
        // body is now [(3,3),(4,3),(4,4),(3,4)]; (3,4) is the tail and vacates this tick
        assertEquals(GameStatus.RUNNING, game.step(Direction.DOWN))
        assertEquals(Position(3, 4), game.head)
    }

    @Test
    fun `eating grows the snake and respawns food on a free cell`() {
        val game = game()
        game.overrideFood(Position(5, 4))
        assertEquals(GameStatus.RUNNING, game.step(Direction.RIGHT))
        assertEquals(5, game.score)
        assertEquals(0, game.stepsSinceFood)
        assertFalse(game.isOccupied(game.food))
        assertTrue(game.food != Position(5, 4))
    }

    @Test
    fun `not eating keeps the length`() {
        val game = game()
        game.overrideFood(Position(0, 0))
        game.step(Direction.UP)
        assertEquals(4, game.score)
        assertEquals(1, game.stepsSinceFood)
    }

    @Test
    fun `starvation kills after maxStepsWithoutFood`() {
        val game = SnakeGame(
            GameConfig(width = 8, height = 8, seed = 1, initialLength = 4, maxStepsWithoutFood = 5)
        )
        game.overrideFood(Position(0, 0))
        val circle = listOf(Direction.UP, Direction.RIGHT, Direction.DOWN, Direction.LEFT)
        var moves = 0
        while (game.status == GameStatus.RUNNING) {
            game.step(circle[moves % circle.size])
            moves++
        }
        assertEquals(DeathReason.STARVED, game.deathReason)
        assertEquals(5, moves)
    }

    @Test
    fun `same seed and strategy produce identical games`() {
        val config = GameConfig(width = 20, height = 20, seed = 123)
        val first = GameRunner.play(config, GreedyStrategy())
        val second = GameRunner.play(config, GreedyStrategy())
        assertEquals(first, second)
    }

    @Test
    fun `different seeds produce different food`() {
        val positions = (0L until 50L).map { seed ->
            SnakeGame(GameConfig(width = 20, height = 20, seed = seed)).food
        }
        assertTrue(positions.distinct().size > 1)
    }
}
