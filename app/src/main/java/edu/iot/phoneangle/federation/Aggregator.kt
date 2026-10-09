package edu.iot.phoneangle.federation

import edu.iot.phoneangle.ml.FedAvg

/** Combines compatible client updates for one round into the next global model. */
object Aggregator {

    /**
     * @param round the round being produced (updates must all carry this round)
     * @throws IllegalArgumentException on empty input, wrong round, or incompatible updates
     */
    fun aggregate(round: Int, updates: List<ModelPayload>): ModelPayload {
        require(updates.isNotEmpty()) { "No updates to aggregate for round $round" }
        val ref = updates.first()
        updates.forEach { u ->
            require(u.type == PayloadType.UPDATE) { "${u.clientId}: not an update payload" }
            require(u.round == round) { "${u.clientId}: update is for round ${u.round}, expected $round" }
            Compat.problem(ref, u)?.let { throw IllegalArgumentException("${u.clientId}: incompatible update ($it)") }
        }
        val model = FedAvg.aggregate(
            updates.map { FedAvg.ClientUpdate(it.clientId, it.sampleCount.coerceAtLeast(1), it.model) },
        )
        val poseCounts = buildMap<String, Int> {
            updates.forEach { u -> u.poseCounts.forEach { (k, v) -> put(k, (get(k) ?: 0) + v) } }
        }
        return ModelPayload(
            type = PayloadType.GLOBAL,
            clientId = "global",
            clientRole = "",
            round = round,
            sampleCount = updates.sumOf { it.sampleCount },
            poseCounts = poseCounts,
            sensorConfig = ref.sensorConfig,
            preprocessingVersion = ref.preprocessingVersion,
            model = model,
            contributors = updates.map { it.clientId }.sorted(),
        )
    }
}
