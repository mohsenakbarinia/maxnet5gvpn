package com.example.core.resilience

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Thread-safe bounded ring buffer (max [maxCapacity] = 200 records) of sanitized [FailureRecord]s.
 * Never stores raw user server hostnames, IPs, UUIDs, passwords, or subscription URLs.
 */
class FailureLog(
    private val maxCapacity: Int = 200
) {
    private val lock = Any()
    private val buffer = ArrayDeque<FailureRecord>(maxCapacity)
    private val _recordsFlow = MutableStateFlow<List<FailureRecord>>(emptyList())
    val recordsFlow: StateFlow<List<FailureRecord>> = _recordsFlow.asStateFlow()

    fun record(entry: FailureRecord) {
        synchronized(lock) {
            if (buffer.size >= maxCapacity) {
                buffer.removeFirst()
            }
            buffer.addLast(entry)
            _recordsFlow.value = buffer.toList().asReversed()
        }
    }

    fun snapshot(): List<FailureRecord> = synchronized(lock) {
        buffer.toList().asReversed()
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _recordsFlow.value = emptyList()
        }
    }

    fun exportSanitizedJson(): String {
        val list = snapshot()
        val arr = JSONArray()
        for (item in list) {
            val obj = JSONObject()
                .put("timestampEpochMs", item.timestampEpochMs)
                .put("networkType", item.networkType.name)
                .put("nationalInternetState", item.nationalInternetState.name)
                .put("nationalStateConfidence", item.nationalStateConfidence.toDouble())
                .put("domesticReachable", item.domesticReachable.name)
                .put("endpointId", item.endpointId)
                .put("transport", item.transport)
                .put("portBucket", item.portBucket)
                .put("failureCategory", item.failureCategory.name)
                .put("rttMs", item.rttMs ?: JSONObject.NULL)
                .put("recoveryActionTaken", item.recoveryActionTaken)
                .put("outcome", item.outcome.name)
            arr.put(obj)
        }
        return arr.toString(2)
    }

    fun restoreFromJson(jsonString: String) {
        if (jsonString.isBlank()) return
        runCatching {
            val arr = JSONArray(jsonString)
            synchronized(lock) {
                buffer.clear()
                val count = arr.length().coerceAtMost(maxCapacity)
                for (i in (count - 1) downTo 0) {
                    val obj = arr.getJSONObject(i)
                    val record = FailureRecord(
                        timestampEpochMs = obj.optLong("timestampEpochMs", 0L),
                        networkType = runCatching {
                            TransportKind.valueOf(obj.optString("networkType", "UNKNOWN"))
                        }.getOrDefault(TransportKind.UNKNOWN),
                        nationalInternetState = runCatching {
                            NationalInternetState.valueOf(obj.optString("nationalInternetState", "UNKNOWN"))
                        }.getOrDefault(NationalInternetState.UNKNOWN),
                        nationalStateConfidence = obj.optDouble("nationalStateConfidence", 0.0).toFloat(),
                        domesticReachable = runCatching {
                            DomesticReachabilityState.valueOf(obj.optString("domesticReachable", "NOT_TESTED"))
                        }.getOrDefault(DomesticReachabilityState.NOT_TESTED),
                        endpointId = obj.optString("endpointId", "ep_unknown"),
                        transport = obj.optString("transport", "UNKNOWN"),
                        portBucket = obj.optString("portBucket", "unknown"),
                        failureCategory = runCatching {
                            FailureCategory.valueOf(obj.optString("failureCategory", "UNKNOWN_FAILURE"))
                        }.getOrDefault(FailureCategory.UNKNOWN_FAILURE),
                        rttMs = if (obj.isNull("rttMs")) null else obj.optLong("rttMs"),
                        recoveryActionTaken = obj.optString("recoveryActionTaken", "NONE"),
                        outcome = runCatching {
                            RecoveryOutcome.valueOf(obj.optString("outcome", "PENDING"))
                        }.getOrDefault(RecoveryOutcome.PENDING)
                    )
                    buffer.addLast(record)
                }
                _recordsFlow.value = buffer.toList().asReversed()
            }
        }
    }
}
