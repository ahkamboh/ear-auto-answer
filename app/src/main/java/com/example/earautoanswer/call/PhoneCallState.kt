package com.example.earautoanswer.call

/**
 * The only three call states this app cares about. Deliberately coarser than
 * the platform's states, and deliberately carrying no phone number.
 */
enum class PhoneCallState {
    /** No call. Includes disconnected and rejected. */
    IDLE,

    /** An inbound call is ringing. The only state in which auto-answer may act. */
    RINGING,

    /** A call is connected — inbound or outbound. Never a trigger. */
    ACTIVE,
}
