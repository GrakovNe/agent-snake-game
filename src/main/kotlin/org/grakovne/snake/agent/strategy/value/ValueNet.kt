package org.grakovne.snake.agent.strategy.value

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

/**
 * ONNX value network: predicts the expected endgame deficit (area - final score) of a
 * loop state. Input planes mirror train/train.py exactly:
 *   0: vacate time normalized by body length, 1: free mask, 2: head one-hot, 3: fill.
 * Output is deficit / (2 * side) — the per-size normalization of the trainer.
 */
class ValueNet(modelPath: String, private val width: Int, private val height: Int) : AutoCloseable {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = createSession(modelPath)

    /** File path first; falls back to the model embedded in the jar (classpath root). */
    private fun createSession(modelPath: String): OrtSession {
        val file = java.io.File(modelPath)
        if (file.exists()) return env.createSession(file.absolutePath, OrtSession.SessionOptions())
        val resource = javaClass.getResourceAsStream("/value-net.onnx")
            ?: error("value net not found: neither $modelPath nor embedded /value-net.onnx")
        return env.createSession(resource.readBytes(), OrtSession.SessionOptions())
    }
    private val planes = FloatArray(4 * width * height)

    /** Predicted deficit in cells (lower = better) for a head-first body cell list. */
    @Synchronized
    fun predictDeficit(body: IntArray): Double {
        val area = width * height
        java.util.Arrays.fill(planes, 0f)
        val length = body.size
        for (i in body.indices) {
            planes[body[i]] = (length - i).toFloat() / length
        }
        for (cell in 0 until area) {
            if (planes[cell] == 0f) planes[area + cell] = 1f
        }
        planes[2 * area + body[0]] = 1f
        val fill = length.toFloat() / area
        java.util.Arrays.fill(planes, 3 * area, 4 * area, fill)

        OnnxTensor.createTensor(
            env, FloatBuffer.wrap(planes), longArrayOf(1, 4, height.toLong(), width.toLong()),
        ).use { tensor ->
            session.run(mapOf("planes" to tensor)).use { result ->
                val value = when (val out = result[0].value) {
                    is FloatArray -> out[0]
                    is Array<*> -> when (val first = out[0]) {
                        is FloatArray -> first[0]
                        else -> first as Float
                    }
                    else -> out as Float
                }
                return value * 2.0 * width
            }
        }
    }

    override fun close() {
        session.close()
    }

    companion object {
        @Volatile
        private var shared: ValueNet? = null

        /** Process-wide instance (the session is thread-safe via the synchronized call). */
        fun sharedFor(modelPath: String, width: Int, height: Int): ValueNet {
            val current = shared
            if (current != null && current.width == width && current.height == height) return current
            synchronized(this) {
                val again = shared
                if (again != null && again.width == width && again.height == height) return again
                val created = ValueNet(modelPath, width, height)
                shared = created
                return created
            }
        }
    }
}
