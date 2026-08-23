package com.m57.hermescontrol.ui.system

import com.m57.hermescontrol.data.model.UpdateReceipt
import com.m57.hermescontrol.data.model.UpdateReceiptResponse
import com.m57.hermescontrol.data.model.UpdateReceiptSummary

/**
 * Render a compact, popup-friendly summary of an update receipt (issue #958).
 *
 * Returns an empty list when there is nothing to show (no receipt, old
 * backend, or any malformed payload) so callers can skip appending entirely.
 *
 * Pure + [internal] so it is unit-testable without mocking the API layer.
 */
internal fun formatUpdateReceiptLines(receipt: UpdateReceiptResponse?): List<String> {
    if (receipt == null) return emptyList()
    val summary: UpdateReceiptSummary? = receipt.summary
    val full: UpdateReceipt? = receipt.receipt
    if (summary == null && full == null) return emptyList()

    val lines = mutableListOf<String>()
    lines.add("Update receipt")

    val outcome = summary?.outcome ?: full?.outcome
    if (outcome != null) lines.add("Outcome: $outcome")

    val pre = full?.preUpdate
    val post = full?.postUpdate
    if (pre != null || post != null) {
        val from = pre?.version ?: pre?.sha?.take(8) ?: "?"
        val to = post?.version ?: post?.sha?.take(8) ?: "?"
        lines.add("Version: $from → $to")
    } else if (summary?.postVersion != null) {
        lines.add("Version: → ${summary.postVersion}")
    }

    val fleet = full?.fleet
    if (!fleet.isNullOrEmpty()) {
        val states = fleet.map { "${it.profile ?: "?"}: ${it.state ?: "?"}" }
        lines.add("Fleet: ${states.joinToString(", ")}")
    } else if (summary != null && !summary.fleetStates.isNullOrEmpty()) {
        lines.add("Fleet: ${summary.fleetStates.joinToString(", ")}")
    }

    return lines
}
