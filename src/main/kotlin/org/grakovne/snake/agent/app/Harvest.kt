package org.grakovne.snake.agent.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.grakovne.snake.agent.core.GameConfig
import org.grakovne.snake.agent.core.Position
import org.grakovne.snake.agent.sim.Arena
import org.grakovne.snake.agent.sim.Rollouts
import org.grakovne.snake.agent.strategy.SafeGreedyStrategy
import java.io.File
import kotlin.random.Random

/**
 * Training-data harvester for the value network, shardable across machines.
 *
 * Phase 1: self-play games dump raw post-eat endgame states (head-first cell lists).
 * Phase 2: each state is labeled with the mean final score of R champion-policy
 * rollouts — the expensive train-time signal that replaces rollouts at inference.
 *
 * Output line format: <label> <width> <height> <cell> <cell> ...
 * (the cell order IS the vacate schedule: everything a network input needs).
 *
 * ./gradlew harvest -Psize=30 -Pgames=200 -PseedFrom=0 -Prollouts=32
 * Shard by giving each machine its own -PseedFrom range; concatenate outputs.
 */
fun main() {
    val size = intProp("size", 30)
    val games = intProp("games", 200)
    val seedFrom = longProp("seedFrom", 0)
    val rollouts = intProp("rollouts", 32)
    val parallelism = intProp("parallelism", Runtime.getRuntime().availableProcessors())
    val outPath = prop("out", "data/planes-$size-seed$seedFrom.txt")

    println("harvest: field=${size}x$size games=$games seedFrom=$seedFrom rollouts=$rollouts -> $outPath")

    // Phase 1: play games, dump endgame post-eat states.
    val perGame = arrayOfNulls<MutableList<IntArray>>(games)
    val startedAt = System.nanoTime()
    Arena(parallelism).evaluate(
        config = GameConfig(width = size, height = size, seed = seedFrom),
        games = games,
        baseSeed = seedFrom,
    ) { index ->
        val states = ArrayList<IntArray>()
        perGame[index] = states
        SafeGreedyStrategy(
            timeAware = false,
            guardHoles = true,
            random = Random(seedFrom + index),
            knobs = Rollouts.championKnobs,
        ).also { it.stateObserver = { state -> states.add(state) } }
    }
    val states = perGame.filterNotNull().flatten()
    println(
        "phase 1: ${states.size} states from $games games in %.1fs"
            .format((System.nanoTime() - startedAt) / 1e9)
    )

    // Phase 2: label states by rollout means, in parallel, appending in chunks — a
    // preempted VM or a killed process loses at most one chunk, and a rerun with the
    // same seedFrom resumes from the already-written line count (states are
    // deterministic given the seed range).
    val out = File(outPath)
    out.parentFile?.mkdirs()
    val alreadyDone = if (out.exists()) out.readLines().count { it.isNotBlank() } else 0
    if (alreadyDone > 0) println("resuming: $alreadyDone samples already labeled")

    val labelStart = System.nanoTime()
    var written = alreadyDone
    val chunkSize = 512
    while (written < states.size) {
        val chunk = states.subList(written, minOf(written + chunkSize, states.size))
        val base = written
        val labels = runBlocking {
            chunk.mapIndexed { offset, state ->
                async(Dispatchers.Default) {
                    val body = state.map { Position(it % size, it / size) }
                    var total = 0.0
                    repeat(rollouts) { r ->
                        total += Rollouts.playOut(
                            size, size, body,
                            seedFrom * 1_000_003 + (base + offset) * 977L + r,
                        )
                    }
                    total / rollouts
                }
            }.awaitAll()
        }
        java.io.FileWriter(out, true).buffered().use { writer ->
            chunk.forEachIndexed { offset, state ->
                writer.write("%.3f $size $size ".format(java.util.Locale.ROOT, labels[offset]))
                writer.write(state.joinToString(" "))
                writer.write("\n")
            }
        }
        written += chunk.size
        println(
            "labeled %d/%d (%.0f rollouts/s)".format(
                written, states.size,
                (written - alreadyDone).toDouble() * rollouts /
                    ((System.nanoTime() - labelStart) / 1e9),
            )
        )
    }
    File("$outPath.done").writeText("$written\n")
    println("done: $written samples in $outPath")
}
