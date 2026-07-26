package org.grakovne.snake.agent.app

import java.io.File

/**
 * Ridge regression of the outcome label on loop features; writes data/weights.txt
 * consumed by the "learned" strategy.
 *
 * ./gradlew fit -Psize=30
 */
fun main() {
    val size = intProp("size", 30)
    val lambda = 1e-3

    val rows = File("data/loops-$size.csv").readLines().filter { it.isNotBlank() }
    require(rows.isNotEmpty()) { "no data; run collect first" }
    val dim = rows.first().split(",").size - 1

    val xtx = Array(dim) { DoubleArray(dim) }
    val xty = DoubleArray(dim)
    var positives = 0
    for (line in rows) {
        val parts = line.split(",")
        val label = parts.last().toDouble()
        if (label > 0.5) positives++
        val x = DoubleArray(dim) { parts[it].toDouble() }
        for (i in 0 until dim) {
            xty[i] += x[i] * label
            for (j in 0 until dim) {
                xtx[i][j] += x[i] * x[j]
            }
        }
    }
    for (i in 0 until dim) xtx[i][i] += lambda * rows.size

    val weights = solve(xtx, xty)

    // in-sample ranking quality: how often a positive row scores above a negative one
    var correct = 0L
    var total = 0L
    val scored = rows.map { line ->
        val parts = line.split(",")
        var s = 0.0
        for (i in 0 until dim) s += weights[i] * parts[i].toDouble()
        s to parts.last().toDouble()
    }
    val pos = scored.filter { it.second > 0.5 }.map { it.first }.sorted()
    val neg = scored.filter { it.second <= 0.5 }.map { it.first }.sorted()
    if (pos.isNotEmpty() && neg.isNotEmpty()) {
        var j = 0
        for (p in pos) {
            while (j < neg.size && neg[j] < p) j++
            correct += j
            total += neg.size.toLong()
        }
    }
    val auc = if (total > 0) correct.toDouble() / total else Double.NaN

    println("rows=${rows.size} positives=$positives dim=$dim")
    println("AUC(in-sample)=%.4f".format(auc))
    println("weights:")
    weights.forEachIndexed { i, w -> println("  f$i = %.6f".format(w)) }

    File("data/weights.txt").writeText(weights.joinToString(","))
    println("written to data/weights.txt")
}

private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
    val n = b.size
    val m = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) a[i][j] else b[i] } }
    for (col in 0 until n) {
        var pivot = col
        for (r in col + 1 until n) {
            if (kotlin.math.abs(m[r][col]) > kotlin.math.abs(m[pivot][col])) pivot = r
        }
        val tmp = m[col]; m[col] = m[pivot]; m[pivot] = tmp
        val div = m[col][col]
        require(kotlin.math.abs(div) > 1e-12) { "singular system" }
        for (j in col..n) m[col][j] /= div
        for (r in 0 until n) {
            if (r == col) continue
            val factor = m[r][col]
            for (j in col..n) m[r][j] -= factor * m[col][j]
        }
    }
    return DoubleArray(n) { m[it][n] }
}
