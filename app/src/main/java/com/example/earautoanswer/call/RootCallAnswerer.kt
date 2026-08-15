package com.example.earautoanswer.call

import com.example.earautoanswer.root.RootExecutor

/**
 * Privileged fallback, used only when the telecom path is unavailable — the user
 * declined ANSWER_PHONE_CALLS, or an OEM build refuses acceptRingingCall().
 *
 * It injects KEYCODE_CALL through [RootExecutor.Command.ANSWER_CALL], which the
 * framework interprets as the hardware answer key. No other privileged command
 * exists in this app.
 */
class RootCallAnswerer : CallAnswerer {

    override val name: String = "root"

    override suspend fun isAvailable(): Boolean = RootExecutor.isRootAvailable()

    override suspend fun answer(): Boolean {
        // `input keyevent` exits 0 once the event has been injected; a non-zero
        // exit means su denied us or the injection failed outright.
        val result = RootExecutor.run(RootExecutor.Command.ANSWER_CALL)
        return result.isSuccess
    }
}
