package com.itsaky.androidide.agent.opencode.acp

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** A JSON-RPC error, either received from the agent or to be sent back to it. */
class AcpRpcException(val code: Int, message: String, val data: JsonElement? = null) : Exception(message)

/**
 * ACP stdio transport.
 *
 * Per the ACP transport spec: the client launches the agent as a subprocess, messages are
 * JSON-RPC 2.0 objects delimited by "\n" (never containing raw newlines), stdout carries only
 * protocol messages and stderr is free-form logging.
 *
 * Notifications are dispatched inline on the reader thread so their order is preserved
 * relative to responses (e.g. all session/update chunks arrive before the session/prompt
 * response). Incoming *requests* run in their own coroutine so a slow handler (like waiting
 * for the user to approve something) never blocks the reader.
 */
class AcpConnection(
    private val process: Process,
    private val scope: CoroutineScope,
    private val requestHandler: suspend (method: String, params: JsonObject?) -> JsonElement?,
    private val notificationHandler: (method: String, params: JsonObject?) -> Unit,
    private val onClosed: (reason: String) -> Unit
) {
    private companion object {
        const val TAG = "AcpConnection"
        const val MAX_STDERR_LINES = 40
    }

    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement?>>()
    private val writeLock = Any()
    private val writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
    private val stderrTail = ArrayDeque<String>()
    private val closing = AtomicBoolean(false)

    fun start() {
        scope.launch(Dispatchers.IO) { readLoop() }
        scope.launch(Dispatchers.IO) {
            runCatching {
                process.errorStream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    Log.d(TAG, "agent stderr: $line")
                    synchronized(stderrTail) {
                        stderrTail.addLast(line)
                        while (stderrTail.size > MAX_STDERR_LINES) stderrTail.removeFirst()
                    }
                }
            }
        }
    }

    /** Sends a request and suspends until the agent answers. Throws [AcpRpcException] on error. */
    suspend fun request(method: String, params: JsonObject? = null): JsonElement? {
        if (closing.get()) throw IOException("Agent connection is closed")
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonElement?>()
        pending[id] = deferred
        try {
            send(JsonObject().apply {
                addProperty("jsonrpc", "2.0")
                addProperty("id", id)
                addProperty("method", method)
                if (params != null) add("params", params)
            })
            return deferred.await()
        } finally {
            pending.remove(id)
        }
    }

    fun notify(method: String, params: JsonObject? = null) {
        send(JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("method", method)
            if (params != null) add("params", params)
        })
    }

    fun close() {
        if (!closing.compareAndSet(false, true)) return
        failPending(IOException("Agent connection closed"))
        runCatching { writer.close() }
        runCatching { process.destroy() }
        scope.launch(Dispatchers.IO) {
            delay(1500)
            if (process.isAlive) runCatching { process.destroyForcibly() }
        }
    }

    fun recentStderr(): String =
        synchronized(stderrTail) { stderrTail.joinToString("\n") }

    // ---- internals -------------------------------------------------------------------------

    private fun send(message: JsonObject) {
        // JsonElement.toString() is compact and escapes control characters, so it never
        // contains a raw newline, as the stdio transport requires.
        val line = message.toString()
        synchronized(writeLock) {
            writer.write(line)
            writer.write("\n")
            writer.flush()
        }
    }

    private fun readLoop() {
        var reason = "Agent process ended"
        try {
            process.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    handleLine(line)
                }
            }
        } catch (e: Exception) {
            reason = "Connection error: ${e.message}"
        } finally {
            if (closing.compareAndSet(false, true)) {
                failPending(IOException(reason))
                val stderr = recentStderr()
                onClosed(if (stderr.isBlank()) reason else "$reason\n$stderr")
            }
        }
    }

    private fun handleLine(line: String) {
        val parsed = try {
            JsonParser.parseString(line)
        } catch (e: Exception) {
            Log.w(TAG, "Ignoring non-JSON line from agent stdout: ${line.take(200)}")
            return
        }
        when {
            parsed.isJsonObject -> handleMessage(parsed.asJsonObject)
            parsed.isJsonArray -> (parsed as JsonArray).forEach { if (it.isJsonObject) handleMessage(it.asJsonObject) }
        }
    }

    private fun handleMessage(obj: JsonObject) {
        val method = obj.get("method")?.takeIf { it.isJsonPrimitive }?.asString
        val id = obj.get("id")?.takeIf { !it.isJsonNull }

        if (method != null) {
            val params = obj.get("params")?.takeIf { it.isJsonObject }?.asJsonObject
            if (id != null) {
                scope.launch(Dispatchers.IO) { respond(id, method, params) }
            } else {
                try {
                    notificationHandler(method, params)
                } catch (e: Exception) {
                    Log.e(TAG, "Notification handler failed for $method", e)
                }
            }
        } else if (id != null) {
            val key = runCatching { id.asLong }.getOrNull() ?: return
            val deferred = pending.remove(key) ?: return
            val error = obj.get("error")?.takeIf { it.isJsonObject }?.asJsonObject
            if (error != null) {
                deferred.completeExceptionally(
                    AcpRpcException(
                        code = error.get("code")?.takeIf { it.isJsonPrimitive }?.asInt ?: -32603,
                        message = error.get("message")?.takeIf { it.isJsonPrimitive }?.asString ?: "Agent error",
                        data = error.get("data")
                    )
                )
            } else {
                deferred.complete(obj.get("result"))
            }
        }
    }

    private suspend fun respond(id: JsonElement, method: String, params: JsonObject?) {
        val response = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            add("id", id)
        }
        try {
            response.add("result", requestHandler(method, params) ?: JsonNull.INSTANCE)
        } catch (e: AcpRpcException) {
            response.add("error", errorObject(e.code, e.message ?: "Error", e.data))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Request handler failed for $method", e)
            response.add("error", errorObject(-32603, e.message ?: "Internal error", null))
        }
        try {
            send(response)
        } catch (e: IOException) {
            Log.w(TAG, "Could not deliver response for $method: ${e.message}")
        }
    }

    private fun errorObject(code: Int, message: String, data: JsonElement?) = JsonObject().apply {
        addProperty("code", code)
        addProperty("message", message)
        if (data != null && !data.isJsonNull) add("data", data)
    }

    private fun failPending(error: Exception) {
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }
}
