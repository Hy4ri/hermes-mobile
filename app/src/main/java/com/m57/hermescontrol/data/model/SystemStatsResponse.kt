package com.m57.hermescontrol.data.model

/**
 * Wrapper around the /api/system/stats response.
 *
 * The payload shape can change across Hermes versions, so we keep it
 * flexible with a Map and provide convenience accessors for the
 * most commonly used fields.
 */
data class SystemStatsResponse(
    val cpu: Map<String, Any?>? = null,
    val memory: Map<String, Any?>? = null,
    val disk: Map<String, Any?>? = null,
    val uptime: Any? = null,
    val extra: Map<String, Any?>? = null,
) {
    /** CPU usage percentage, if available. */
    val cpuPercent: Double?
        get() = (cpu?.get("percent") as? Number)?.toDouble()

    /** Memory usage percentage, if available. */
    val memoryPercent: Double?
        get() = (memory?.get("percent") as? Number)?.toDouble()
}
