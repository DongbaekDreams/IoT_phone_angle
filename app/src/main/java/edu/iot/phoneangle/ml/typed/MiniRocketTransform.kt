package edu.iot.phoneangle.ml.typed

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Multivariate MiniRocket (Dempster et al.): length-9 kernels, dilated conv over random
 * channel subsets, PPV features. Kernels / dilations / channel picks are fixed from [SEED]
 * so phones share the transform; biases are fit on local train and stored in the model bundle.
 */
class MiniRocketTransform(
    private val nChannels: Int = N_CHANNELS,
    private val inputLength: Int = INPUT_LENGTH,
    private val numFeatures: Int = NUM_FEATURES,
    seed: Long = SEED,
) {
    private val kernels: Array<FloatArray> = buildKernels()
    private val specs: Array<FeatureSpec>
    val featureDim: Int get() = specs.size

    init {
        require(nChannels >= 1)
        require(numFeatures >= kernels.size)
        val maxDilation = max(1, (inputLength - 1) / (KERNEL_LEN - 1))
        val dilations = buildDilations(maxDilation)
        val rng = Random(seed)
        val per = numFeatures / kernels.size
        val remainder = numFeatures % kernels.size
        val list = ArrayList<FeatureSpec>(numFeatures)
        for (ki in kernels.indices) {
            val count = per + if (ki < remainder) 1 else 0
            repeat(count) {
                val dilation = dilations[rng.nextInt(dilations.size)]
                val nPick = 1 + rng.nextInt(nChannels) // 1..C — mixes channels when >1
                val channels = IntArray(nChannels) { it }
                // Fisher–Yates partial shuffle for a random subset
                for (i in 0 until nPick) {
                    val j = i + rng.nextInt(nChannels - i)
                    val tmp = channels[i]
                    channels[i] = channels[j]
                    channels[j] = tmp
                }
                list += FeatureSpec(ki, dilation, channels.copyOf(nPick))
            }
        }
        specs = list.toTypedArray()
    }

    /** Fit one bias per feature from quantiles of training convolutions. */
    fun fitBiases(train: List<Array<FloatArray>>): FloatArray {
        require(train.isNotEmpty())
        val biases = FloatArray(specs.size)
        val quantiles = floatArrayOf(0.15f, 0.25f, 0.5f, 0.75f, 0.85f)
        val rng = Random(SEED xor 0xB1A5L)
        for (fi in specs.indices) {
            val sampleCap = min(32, train.size)
            val values = FloatArray(sampleCap * inputLength)
            var n = 0
            for (si in 0 until sampleCap) {
                val conv = convolve(train[si], specs[fi])
                for (v in conv) values[n++] = v
            }
            values.sort(0, n)
            val q = quantiles[rng.nextInt(quantiles.size)]
            val idx = ((n - 1) * q).toInt().coerceIn(0, n - 1)
            biases[fi] = values[idx]
        }
        return biases
    }

    fun transform(channels: Array<FloatArray>, biases: FloatArray): FloatArray {
        val out = FloatArray(specs.size)
        transformInto(channels, biases, out)
        return out
    }

    /** In-place transform into a caller-owned buffer (avoids per-inference allocation). */
    fun transformInto(channels: Array<FloatArray>, biases: FloatArray, out: FloatArray) {
        require(channels.size == nChannels)
        require(biases.size == specs.size)
        require(out.size >= specs.size)
        for (c in channels) require(c.size == inputLength)
        val invLen = 1f / inputLength
        val len = inputLength
        for (fi in specs.indices) {
            val spec = specs[fi]
            val w = kernels[spec.kernelIndex]
            val d = spec.dilation
            val pad = ((KERNEL_LEN - 1) * d) / 2
            val bias = biases[fi]
            val picked = spec.channels
            val nPick = picked.size
            var pos = 0
            for (t in 0 until len) {
                var sum = 0f
                for (j in 0 until KERNEL_LEN) {
                    val idx = t + j * d - pad
                    if (idx in 0 until len) {
                        val wt = w[j]
                        var k = 0
                        while (k < nPick) {
                            sum += channels[picked[k]][idx] * wt
                            k++
                        }
                    }
                }
                if (sum > bias) pos++
            }
            out[fi] = pos * invLen
        }
    }

    private fun convolve(channels: Array<FloatArray>, spec: FeatureSpec): FloatArray {
        val w = kernels[spec.kernelIndex]
        val d = spec.dilation
        val pad = ((KERNEL_LEN - 1) * d) / 2
        val out = FloatArray(inputLength)
        val picked = spec.channels
        val nPick = picked.size
        val len = inputLength
        for (t in 0 until len) {
            var sum = 0f
            for (j in 0 until KERNEL_LEN) {
                val idx = t + j * d - pad
                if (idx in 0 until len) {
                    val wt = w[j]
                    var k = 0
                    while (k < nPick) {
                        sum += channels[picked[k]][idx] * wt
                        k++
                    }
                }
            }
            out[t] = sum
        }
        return out
    }

    private data class FeatureSpec(
        val kernelIndex: Int,
        val dilation: Int,
        val channels: IntArray,
    )

    companion object {
        const val VERSION = 2
        const val SEED = 8223L
        const val INPUT_LENGTH = 64
        const val KERNEL_LEN = 9
        const val N_CHANNELS = 6 // ax,ay,az,gx,gy,gz
        /** One feature per kernel — phone-friendly; was 336 and crushed live UI. */
        const val NUM_FEATURES = 84

        val Default = MiniRocketTransform()

        /** C(9,3)=84 kernels with three +2 weights and six -1 weights. */
        private fun buildKernels(): Array<FloatArray> {
            val list = ArrayList<FloatArray>(84)
            for (a in 0 until KERNEL_LEN) {
                for (b in a + 1 until KERNEL_LEN) {
                    for (c in b + 1 until KERNEL_LEN) {
                        val w = FloatArray(KERNEL_LEN) { -1f }
                        w[a] = 2f
                        w[b] = 2f
                        w[c] = 2f
                        list += w
                    }
                }
            }
            check(list.size == 84)
            return list.toTypedArray()
        }

        private fun buildDilations(maxDilation: Int): IntArray {
            if (maxDilation <= 1) return intArrayOf(1)
            val maxExp = ln(maxDilation.toDouble()).toInt()
            val list = (0..maxExp).map { 1 shl it }.filter { it <= maxDilation }
            return if (list.isEmpty()) intArrayOf(1) else list.toIntArray()
        }
    }
}

/** Mean/std scaler for Softmax inputs (fit on train PPV features). */
class FeatureScaler {
    private var mu: FloatArray? = null
    private var sigma: FloatArray? = null

    val fitted: Boolean get() = mu != null

    fun fit(rows: List<FloatArray>) {
        require(rows.isNotEmpty())
        val dim = rows[0].size
        val n = rows.size.toFloat()
        val mean = FloatArray(dim)
        for (row in rows) for (i in 0 until dim) mean[i] += row[i]
        for (i in 0 until dim) mean[i] /= n
        val std = FloatArray(dim)
        for (row in rows) for (i in 0 until dim) {
            val d = row[i] - mean[i]
            std[i] += d * d
        }
        for (i in 0 until dim) std[i] = sqrt(std[i] / n) + 1e-6f
        mu = mean
        sigma = std
    }

    fun transform(row: FloatArray): FloatArray {
        val out = FloatArray(row.size)
        transformInto(row, out)
        return out
    }

    fun transformInto(row: FloatArray, out: FloatArray) {
        val m = mu ?: error("FeatureScaler not fitted")
        val s = sigma!!
        require(out.size >= row.size)
        for (i in row.indices) out[i] = (row[i] - m[i]) / s[i]
    }

    fun fitTransform(rows: List<FloatArray>): List<FloatArray> {
        fit(rows)
        return rows.map { transform(it) }
    }

    fun toArrays(): Pair<FloatArray, FloatArray> = mu!!.copyOf() to sigma!!.copyOf()

    fun load(mu: FloatArray, sigma: FloatArray) {
        this.mu = mu.copyOf()
        this.sigma = sigma.copyOf()
    }
}
