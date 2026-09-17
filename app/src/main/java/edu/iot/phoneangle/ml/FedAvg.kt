package edu.iot.phoneangle.ml

/**
 * Federated Averaging over compatible softmax models (weighted by sample counts).
 */
object FedAvg {

    fun aggregate(updates: List<ClientUpdate>): SoftmaxClassifier {
        require(updates.isNotEmpty()) { "Need at least one client update" }
        val first = updates.first().model
        val totalN = updates.sumOf { it.sampleCount }.coerceAtLeast(1)
        val featureDim = first.featureDim
        val numClasses = first.numClasses
        val classes = first.classes

        updates.forEach { u ->
            require(u.model.featureDim == featureDim)
            require(u.model.numClasses == numClasses)
            require(u.model.classes == classes)
        }

        val weights = Array(numClasses) { FloatArray(featureDim) }
        val bias = FloatArray(numClasses)

        for (update in updates) {
            val w = update.sampleCount.toFloat() / totalN
            for (c in 0 until numClasses) {
                bias[c] += w * update.model.bias[c]
                for (f in 0 until featureDim) {
                    weights[c][f] += w * update.model.weights[c][f]
                }
            }
        }
        return SoftmaxClassifier(featureDim, classes, weights, bias)
    }

    data class ClientUpdate(
        val clientId: String,
        val sampleCount: Int,
        val model: SoftmaxClassifier,
    )
}
