package com.example.earautoanswer.call

import com.example.earautoanswer.diagnostics.DiagnosticLogger

/**
 * Tries a list of answerers in order of preference and stops at the first one
 * that succeeds. Every attempt is logged so the diagnostics screen shows exactly
 * which path answered — the usual question when a device misbehaves.
 */
class ChainedCallAnswerer(private val answerers: List<CallAnswerer>) : CallAnswerer {

    override val name: String = "chain"

    override suspend fun isAvailable(): Boolean = answerers.any { it.isAvailable() }

    override suspend fun answer(): Boolean {
        for (answerer in answerers) {
            if (!answerer.isAvailable()) {
                DiagnosticLogger.log("Answerer ${answerer.name} unavailable — skipped")
                continue
            }
            val success = answerer.answer()
            DiagnosticLogger.log(
                "Answer via ${answerer.name} -> " + if (success) "success" else "failure",
            )
            if (success) return true
        }
        return false
    }
}
