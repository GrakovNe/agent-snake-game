package org.grakovne.snake.agent.sim

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.grakovne.snake.agent.core.GameConfig
import org.grakovne.snake.agent.strategy.Strategy

/**
 * Runs many games in parallel. This is the workhorse for selection loops
 * (genetic algorithms, hyperparameter search): every candidate is evaluated
 * on the same seed set (baseSeed + gameIndex), so comparisons use common
 * random numbers and are fair.
 *
 * Strategies may be stateful, so a fresh instance is requested per game
 * through the factory parameter.
 */
class Arena(parallelism: Int = Runtime.getRuntime().availableProcessors()) {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.Default.limitedParallelism(parallelism)

    fun evaluate(
        config: GameConfig,
        games: Int,
        baseSeed: Long = config.seed,
        strategyFactory: (gameIndex: Int) -> Strategy,
    ): Evaluation = runBlocking {
        (0 until games)
            .map { index ->
                async(dispatcher) {
                    GameRunner.play(config.copy(seed = baseSeed + index), strategyFactory(index))
                }
            }
            .awaitAll()
            .let(::Evaluation)
    }

    /** Evaluates every candidate on the same seed set, returns them best mean score first. */
    fun <C> tournament(
        candidates: List<C>,
        config: GameConfig,
        gamesPerCandidate: Int,
        baseSeed: Long = config.seed,
        strategyFor: (candidate: C, gameIndex: Int) -> Strategy,
    ): List<Pair<C, Evaluation>> = runBlocking {
        candidates
            .map { candidate ->
                async {
                    val results = (0 until gamesPerCandidate)
                        .map { index ->
                            async(dispatcher) {
                                GameRunner.play(
                                    config.copy(seed = baseSeed + index),
                                    strategyFor(candidate, index),
                                )
                            }
                        }
                        .awaitAll()
                    candidate to Evaluation(results)
                }
            }
            .awaitAll()
            .sortedByDescending { it.second.meanScore }
    }
}
