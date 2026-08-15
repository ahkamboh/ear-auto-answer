package com.example.earautoanswer.root

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * The app's entire privileged surface.
 *
 * Everything about this object is built around one rule: **a caller can never
 * choose what runs as root.** [run] accepts only a value of the closed [Command]
 * enum, the shell text lives inside that enum and nowhere else, and no function
 * here — public, internal or private — takes a command string. Adding a new
 * privileged capability therefore requires editing this file, which is exactly
 * the review checkpoint we want.
 *
 * Root is a *fallback*. On stock Android 14 with ANSWER_PHONE_CALLS granted the
 * telecom path answers calls and none of this ever executes.
 */
object RootExecutor {

    data class Result(val exitCode: Int, val stdout: String, val stderr: String) {
        val isSuccess: Boolean get() = exitCode == 0
    }

    /** Only these fixed commands may ever run. No arbitrary strings, ever. */
    enum class Command(internal val shell: String) {
        /**
         * Root probe. `id -u` prints the effective uid of the shell `su` handed
         * us; "0" means the superuser daemon actually granted the request rather
         * than merely existing on the device.
         */
        PROBE("id -u"),

        /**
         * Injects KEYCODE_CALL (5). The framework treats it as the hardware
         * "answer" key, which accepts the currently ringing call. This is the
         * only privileged action the app ever performs, and it is reached only
         * after the gesture pipeline has already validated pickup + proximity +
         * orientation.
         */
        ANSWER_CALL("input keyevent 5"), // KEYCODE_CALL — accepts a ringing call

        /**
         * Injects KEYCODE_HEADSETHOOK (79).
         *
         * Unlike KEYCODE_CALL — which PhoneWindowManager turns into the same
         * `acceptRingingCall()` the platform blocks for self-managed calls — a media
         * button reaches `CallsManager.onMediaButton()`, which answers the first
         * ringing call with no self-managed check. That makes it the only privileged
         * path that could plausibly answer a WhatsApp call.
         *
         * UNVERIFIED, and the reason this is opt-in and default OFF: the event only
         * gets there if Telecom's MediaSession wins media-button priority, which a
         * music app can take instead. `input keyevent` also exits 0 once the event
         * has been *injected*, not once a call has been *answered*, so this command
         * can report success without answering anything.
         */
    }

    /** `su` could not be spawned at all — no binary, or the request was denied. */
    private const val EXIT_SPAWN_FAILED = -1

    /** The privileged shell outlived its budget and was killed. */
    private const val EXIT_TIMEOUT = -2

    /** Anything else went wrong while talking to the process. */
    private const val EXIT_ERROR = -3

    /** How long to wait for the drain threads once the process has already exited. */
    private const val DRAIN_JOIN_MS = 500L

    private val probeMutex = Mutex()

    @Volatile
    private var cachedRootAvailable: Boolean? = null

    /**
     * True when a superuser daemon exists *and* grants this app root.
     *
     * Cached after the first successful probe: re-probing would pop the Magisk
     * prompt repeatedly and add seconds of latency to the answer path, which is
     * the one place in this app where latency is felt.
     */
    suspend fun isRootAvailable(): Boolean {
        cachedRootAvailable?.let { return it }
        return probeMutex.withLock {
            // Another caller may have completed the probe while we waited.
            val known = cachedRootAvailable
            if (known != null) {
                known
            } else {
                val result = run(Command.PROBE)
                val granted = result.isSuccess && result.stdout.trim() == "0"
                cachedRootAvailable = granted
                granted
            }
        }
    }

    /**
     * Runs one fixed [command] as root. Never throws for a failure: an
     * unavailable, denied or hung `su` comes back as a non-zero [Result].
     * Coroutine cancellation still propagates, as it must.
     */
    suspend fun run(command: Command, timeoutMs: Long = 3_000L): Result =
        withContext(Dispatchers.IO) {
            // Spawning `su` with no arguments starts an interactive privileged
            // shell; the command is fed over stdin below. A missing binary or a
            // denied request fails right here.
            val process = try {
                ProcessBuilder("su").redirectErrorStream(false).start()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                return@withContext Result(
                    EXIT_SPAWN_FAILED,
                    "",
                    t.message ?: "su could not be started",
                )
            }

            try {
                // Both pipes are drained on their own threads. A single-threaded
                // read would deadlock the moment one pipe's buffer filled while
                // we were blocked on the other.
                val stdoutHolder = AtomicReference<String?>(null)
                val stderrHolder = AtomicReference<String?>(null)
                val stdoutThread = drainThread("su-stdout", process.inputStream, stdoutHolder)
                val stderrThread = drainThread("su-stderr", process.errorStream, stderrHolder)

                // Write the one fixed command, then close stdin. The EOF is what
                // makes the privileged shell exit on its own.
                process.outputStream.use { stdin ->
                    stdin.write((command.shell + "\n").toByteArray())
                    stdin.flush()
                }

                val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
                if (!finished) {
                    // The finally block below kills it. Report the timeout rather
                    // than blocking the answer path any longer.
                    return@withContext Result(
                        EXIT_TIMEOUT,
                        stdoutHolder.get().orEmpty(),
                        "su timed out after ${timeoutMs}ms",
                    )
                }

                // The process has exited, so both pipes are at EOF and the drain
                // threads finish immediately. join() also publishes their writes.
                stdoutThread.join(DRAIN_JOIN_MS)
                stderrThread.join(DRAIN_JOIN_MS)

                Result(
                    exitCode = process.exitValue(),
                    stdout = stdoutHolder.get().orEmpty(),
                    stderr = stderrHolder.get().orEmpty(),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Result(EXIT_ERROR, "", t.message ?: t.javaClass.simpleName)
            } finally {
                // Always reap the privileged shell — a leaked root process is the
                // worst possible thing to leave behind.
                try {
                    process.destroy()
                    if (process.isAlive) process.destroyForcibly()
                } catch (t: Throwable) {
                    // Nothing further can be done about a process we cannot kill.
                }
            }
        }

    /**
     * Reads [stream] to EOF on a daemon thread and publishes the text into
     * [holder]. The holder stays null while the read is still in flight, so a
     * caller that gives up early reads a consistent (empty) value instead of a
     * half-written buffer.
     */
    private fun drainThread(
        name: String,
        stream: InputStream,
        holder: AtomicReference<String?>,
    ): Thread {
        val thread = Thread({
            val text = try {
                stream.bufferedReader().use { it.readText() }
            } catch (t: Throwable) {
                ""
            }
            holder.set(text)
        }, name)
        thread.isDaemon = true
        thread.start()
        return thread
    }
}
