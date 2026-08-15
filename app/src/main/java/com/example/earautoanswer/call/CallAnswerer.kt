package com.example.earautoanswer.call

/**
 * One way of answering a ringing call.
 *
 * Implementations are tried in order of preference: the public telecom API
 * first, privileged execution only as a fallback.
 */
interface CallAnswerer {
    /** Short identifier used in diagnostics, e.g. "telecom" or "root". */
    val name: String

    /**
     * Cheap pre-flight check — permission held, binary present, and so on.
     * Must not itself answer anything and must never throw.
     */
    suspend fun isAvailable(): Boolean

    /** Attempts to answer. Returns true only on a confident success. Never throws. */
    suspend fun answer(): Boolean
}
