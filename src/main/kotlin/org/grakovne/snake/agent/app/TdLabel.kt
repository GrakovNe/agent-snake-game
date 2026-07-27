package org.grakovne.snake.agent.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.grakovne.snake.agent.core.GameConfig
import org.grakovne.snake.agent.core.Position
import org.grakovne.snake.agent.core.SnakeGame
import org.grakovne.snake.agent.strategy.search.BoardSearch
import org.grakovne.snake.agent.strategy.value.ValueNet
import java.io.File
import kotlin.random.Random

/**
 * One sweep of fitted value iteration: TD labels without rollout noise.
 *
 * label(S) = E_{spawn c ~ free(S)} [ max_{safe walk w} V_net(postBody(S, w, c)) ]
 * with exact terminal handling (full board = area) and a pessimistic floor
 * (current length) for spawns with no safe walk. Spawn noise is integrated
 * explicitly instead of sampled by rollouts — the S/N wall killer.
 *
 * ./gradlew tdlabel -Pin=data/decided-30.txt -Pout=data/td-30.txt -PspawnCap=14
 */
fun main() {
    val inPath = prop("in", "data/decided-30.txt")
    val outPath = prop("out", "data/td-30.txt")
    val netPath = prop("valueNet", "data/value-net.onnx")
    val spawnCap = intProp("spawnCap", 14)
    val parallelism = intProp("parallelism", Runtime.getRuntime().availableProcessors())

    data class Row(val w: Int, val h: Int, val cells: IntArray)

    val rows = File(inPath).readLines().mapNotNull { line ->
        val parts = line.split(" ")
        if (parts.size < 5) return@mapNotNull null
        val bucketFormat = parts[1].contains('.')
        val base = if (bucketFormat) 2 else 1
        Row(
            parts[base].toInt(), parts[base + 1].toInt(),
            IntArray(parts.size - base - 2) { parts[base + 2 + it].toInt() },
        )
    }
    println("tdlabel: ${rows.size} states from $inPath, net=$netPath, spawnCap=$spawnCap")

    val out = File(outPath)
    out.parentFile?.mkdirs()
    val started = System.nanoTime()

    val labels = runBlocking {
        rows.mapIndexed { index, row ->
            async(Dispatchers.Default) {
                tdBackup(row.w, row.h, row.cells, netPath, spawnCap, Random(index.toLong()))
            }
        }.awaitAll()
    }

    out.bufferedWriter().use { writer ->
        rows.forEachIndexed { i, row ->
            writer.write("%.3f ${row.w} ${row.h} ".format(java.util.Locale.ROOT, labels[i]))
            writer.write(row.cells.joinToString(" "))
            writer.write("\n")
        }
    }
    println(
        "wrote ${rows.size} TD labels to $outPath in %.1fs"
            .format((System.nanoTime() - started) / 1e9)
    )
}

private fun tdBackup(
    w: Int,
    h: Int,
    cells: IntArray,
    netPath: String,
    spawnCap: Int,
    rng: Random,
): Double {
    val area = w * h
    val body = cells.map { Position(it % w, it / w) }
    val occupied = cells.toHashSet()
    val free = (0 until area).filter { it !in occupied }
    if (free.isEmpty()) return area.toDouble()

    val net = ValueNet.sharedFor(netPath, w, h)
    val spawns = if (free.size <= spawnCap) free else free.shuffled(rng).take(spawnCap)

    var total = 0.0
    for (spawn in spawns) {
        val game = SnakeGame(
            GameConfig(width = w, height = h, seed = 1),
            initialBody = body,
            initialFood = Position(spawn % w, spawn / w),
        )
        val board = BoardSearch(w, h)
        board.load(game)

        var best = cells.size.toDouble()   // pessimistic floor: no safe walk found
        val paths = ArrayList<IntArray>(6)
        board.shortestPathFromHead(
            target = spawn, timeAware = false, margin = 0, hugging = true,
        )?.let { paths.add(it) }
        board.shortestPathFromHead(
            target = spawn, timeAware = false, margin = 0,
        )?.let { paths.add(it) }
        board.foodPathSnapshots(rng = null, limit = 6) { paths.add(it) }

        for (path in paths) {
            if (path.size < 2) continue
            val post = board.bodyAfterEating(game.snake, path)
            val value = if (post.size == area) {
                area.toDouble()                       // exact terminal: board full
            } else if (board.tailReachableFor(post, timeAware = false)) {
                area - net.predictDeficit(post)       // net leaf
            } else {
                continue                              // unsafe walk
            }
            if (value > best) best = value
        }
        total += best
    }
    return total / spawns.size
}
