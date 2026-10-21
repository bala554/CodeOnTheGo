package com.itsaky.androidide.agent.opencode.acp

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.itsaky.androidide.agent.opencode.bridge.OpenCodeFileBridge
import com.itsaky.androidide.agent.opencode.bridge.PatchConflictException
import com.itsaky.androidide.agent.opencode.model.DiffLine
import com.itsaky.androidide.agent.opencode.model.DiffType
import com.itsaky.androidide.agent.opencode.model.IdeProjectContext
import com.itsaky.androidide.agent.opencode.model.MessagePart
import com.itsaky.androidide.agent.opencode.model.PatchStatus
import com.itsaky.androidide.agent.opencode.model.ToolApprovalRequest
import com.itsaky.androidide.agent.opencode.model.ToolApprovalStatus
import com.itsaky.androidide.agent.opencode.model.ToolParameters
import com.itsaky.androidide.agent.opencode.model.ToolStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Agent Client Protocol (ACP) client for Code on the Go.
 *
 * Capabilities offered to the agent: fs.readTextFile and fs.writeTextFile only. The terminal
 * capability is deliberately NOT advertised, and any terminal/* or "execute" permission
 * request is refused.
 *
 * Policy (matches the product decision "reads are free, writes need approval"):
 *  - fs/read_text_file: always allowed (inside the project root only)
 *  - fs/write_text_file: allowed only if (a) auto-apply is on, or (b) the user already approved
 *    a permission request that covered that path, or (c) the user approves a prompt shown at
 *    write time. Every write goes through [OpenCodeFileBridge] (containment, protected dirs,
 *    stale-read conflict detection, undo).
 */
class AcpAgentClient(
    private val fileBridge: OpenCodeFileBridge,
    private val scope: CoroutineScope,
    private val autoApply: () -> Boolean
) {
    companion object {
        private const val TAG = "AcpAgentClient"
        private const val PROTOCOL_VERSION = 1
        private const val CLIENT_NAME = "code-on-the-go"
        private const val MAX_DIFF_LINES = 400
        private const val MAX_TEXT = 2000
    }

    private data class ToolSnapshot(
        val title: String = "",
        val status: String? = null,
        val args: String = "",
        val result: String? = null
    )

    @Volatile private var connection: AcpConnection? = null
    @Volatile var sessionId: String? = null
        private set
    @Volatile var agentLabel: String = ""
        private set

    /** Called when the agent process dies or the pipe breaks (not on [disconnect]). */
    var onDisconnected: ((reason: String) -> Unit)? = null

    val isConnected: Boolean get() = connection != null && sessionId != null

    private val _configOptions = MutableStateFlow<List<AcpConfigOption>>(emptyList())

    /** Models / modes (Build, Plan) / effort reported by the agent for the current session. */
    val configOptions: StateFlow<List<AcpConfigOption>> = _configOptions.asStateFlow()

    @Volatile private var turn: AcpTurn? = null
    private val toolStates = ConcurrentHashMap<String, ToolSnapshot>()
    private val pendingDecisions = ConcurrentHashMap<String, CompletableDeferred<Boolean?>>()
    private val grantedPaths = ConcurrentHashMap.newKeySet<String>()
    private val lastRead = ConcurrentHashMap<String, String>()

    // ---- lifecycle -------------------------------------------------------------------------

    /** Handshake (initialize) and create the session. Returns a human-readable agent label. */
    suspend fun connect(process: Process, projectRoot: File): Result<String> = withContext(Dispatchers.IO) {
        disconnect()
        val conn = AcpConnection(
            process = process,
            scope = scope,
            requestHandler = ::handleRequest,
            notificationHandler = ::handleNotification,
            onClosed = { reason ->
                connection = null
                sessionId = null
                _configOptions.value = emptyList()
                cancelPendingDecisions()
                onDisconnected?.invoke(reason)
            }
        )
        conn.start()
        try {
            val init = conn.request("initialize", JsonObject().apply {
                addProperty("protocolVersion", PROTOCOL_VERSION)
                add("clientCapabilities", JsonObject().apply {
                    add("fs", JsonObject().apply {
                        addProperty("readTextFile", true)
                        addProperty("writeTextFile", true)
                    })
                    addProperty("terminal", false)
                })
                add("clientInfo", JsonObject().apply {
                    addProperty("name", CLIENT_NAME)
                    addProperty("version", "1")
                })
            })?.takeIf { it.isJsonObject }?.asJsonObject
                ?: throw IOException("Empty response to initialize.\n${conn.recentStderr()}")

            Log.d(TAG, "initialize -> $init")
            val agentVersion = init.get("protocolVersion")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
            if (agentVersion != PROTOCOL_VERSION) {
                throw IOException("Unsupported ACP protocol version from agent: $agentVersion")
            }
            val info = init.getAsJsonObject("agentInfo")
            agentLabel = listOfNotNull(info?.str("title") ?: info?.str("name"), info?.str("version"))
                .joinToString(" ").ifBlank { "ACP agent" }

            val session = conn.request("session/new", JsonObject().apply {
                addProperty("cwd", projectRoot.canonicalPath)
                add("mcpServers", JsonArray())
            })?.takeIf { it.isJsonObject }?.asJsonObject
            Log.d(TAG, "session/new -> $session") // shows available models / modes / config options
            sessionId = session?.str("sessionId") ?: throw IOException("Agent did not return a sessionId")
            _configOptions.value = parseConfigOptions(session?.getAsJsonArray("configOptions"))
            connection = conn
            Result.success(agentLabel)
        } catch (e: CancellationException) {
            conn.close()
            throw e
        } catch (e: Exception) {
            conn.close()
            Result.failure(e)
        }
    }

    /**
     * Changes a session config option (`model`, `mode` = Build/Plan, `effort`) via
     * `session/set_config_option`. The agent answers with the full updated option list.
     */
    suspend fun setConfigOption(configId: String, value: String): Result<Unit> = withContext(Dispatchers.IO) {
        val conn = connection
        val sid = sessionId
        if (conn == null || sid == null) {
            return@withContext Result.failure(IllegalStateException("Agent is not connected"))
        }
        try {
            val res = conn.request("session/set_config_option", JsonObject().apply {
                addProperty("sessionId", sid)
                addProperty("configId", configId)
                addProperty("value", value)
            })
            val updated = parseConfigOptions(res?.takeIf { it.isJsonObject }?.asJsonObject?.getAsJsonArray("configOptions"))
            _configOptions.value = if (updated.isNotEmpty()) updated
            else _configOptions.value.map { if (it.id == configId) it.withCurrentValue(value) else it }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Starts a fresh ACP session (new conversation context) on the existing connection. */
    suspend fun newSession(projectRoot: File): Result<String> = withContext(Dispatchers.IO) {
        val conn = connection ?: return@withContext Result.failure(IllegalStateException("Agent is not connected"))
        try {
            val res = conn.request("session/new", JsonObject().apply {
                addProperty("cwd", projectRoot.canonicalPath)
                add("mcpServers", JsonArray())
            })?.takeIf { it.isJsonObject }?.asJsonObject
            val id = res?.str("sessionId") ?: throw IOException("Agent did not return a sessionId")
            val previous = _configOptions.value.associate { it.id to it.currentValue }
            sessionId = id
            lastRead.clear()
            grantedPaths.clear()
            _configOptions.value = parseConfigOptions(res?.getAsJsonArray("configOptions"))
            // A new session starts from the agent's defaults; keep what the user picked.
            for (optionId in listOf("model", "mode")) {
                val wanted = previous[optionId] ?: continue
                val option = _configOptions.value.firstOrNull { it.id == optionId } ?: continue
                if (option.currentValue != wanted && option.values.any { it.value == wanted }) {
                    setConfigOption(optionId, wanted)
                }
            }
            Result.success(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun disconnect() {
        connection?.close()
        connection = null
        sessionId = null
        _configOptions.value = emptyList()
        cancelPendingDecisions()
    }

    /** Cancels the running turn (session/cancel) and answers pending permission prompts as cancelled. */
    fun cancel() {
        val sid = sessionId ?: return
        runCatching { connection?.notify("session/cancel", JsonObject().apply { addProperty("sessionId", sid) }) }
        cancelPendingDecisions()
    }

    /** Called by the UI when the user taps approve/reject on an approval card. */
    fun resolveApproval(callId: String, approved: Boolean): Boolean {
        val deferred = pendingDecisions[callId] ?: return false
        return deferred.complete(approved)
    }

    // ---- prompting -------------------------------------------------------------------------

    suspend fun prompt(text: String, context: IdeProjectContext?, turn: AcpTurn): Result<String> =
        withContext(Dispatchers.IO) {
            val conn = connection
            val sid = sessionId
            if (conn == null || sid == null) {
                return@withContext Result.failure(IllegalStateException("Agent is not connected"))
            }
            this@AcpAgentClient.turn = turn
            toolStates.clear()
            grantedPaths.clear()
            try {
                val blocks = JsonArray().apply {
                    add(textBlock(text))
                    contextSummary(context)?.let { add(assistantContextBlock(it)) }
                }
                val result = conn.request("session/prompt", JsonObject().apply {
                    addProperty("sessionId", sid)
                    add("prompt", blocks)
                })
                val stop = result?.takeIf { it.isJsonObject }?.asJsonObject?.str("stopReason")
                Log.d(TAG, "Turn finished: $stop")
                Result.success(turn.fullText)
            } catch (e: CancellationException) {
                runCatching { conn.notify("session/cancel", JsonObject().apply { addProperty("sessionId", sid) }) }
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                cancelPendingDecisions()
                this@AcpAgentClient.turn = null
            }
        }

    // ---- agent -> client notifications -----------------------------------------------------

    private fun handleNotification(method: String, params: JsonObject?) {
        if (method != "session/update") return
        val update = params?.getAsJsonObject("update") ?: return
        if (update.str("sessionUpdate") == "config_option_update") {
            // The agent changed models/agents (or another client did): keep the selectors in sync.
            parseConfigOptions(update.getAsJsonArray("configOptions"))
                .takeIf { it.isNotEmpty() }?.let { _configOptions.value = it }
            return
        }
        val t = turn ?: return
        when (update.str("sessionUpdate")) {
            "agent_message_chunk" -> textOf(update)?.let { t.appendText(it) }
            "agent_thought_chunk" -> textOf(update)?.let { t.appendReasoning(it) }
            "tool_call", "tool_call_update" -> applyToolUpdate(t, update)
            "plan" -> applyPlan(t, update)
            else -> Unit // user_message_chunk, available_commands_update, current_mode_update, ...
        }
    }

    private fun textOf(update: JsonObject): String? =
        update.getAsJsonObject("content")?.takeIf { it.str("type") == "text" }?.str("text")

    private fun applyToolUpdate(t: AcpTurn, update: JsonObject) {
        val id = update.str("toolCallId") ?: return
        val prev = toolStates[id] ?: ToolSnapshot()
        val next = prev.copy(
            title = update.str("title") ?: prev.title,
            status = update.str("status") ?: prev.status,
            args = update.get("rawInput")?.takeIf { !it.isJsonNull }?.toString()?.take(MAX_TEXT)
                ?: locationsText(update) ?: prev.args,
            result = toolContentText(update) ?: prev.result
        )
        toolStates[id] = next
        t.put(
            "tool:$id",
            MessagePart.ToolCall(
                toolName = next.title.ifBlank { "Tool call" },
                arguments = next.args,
                status = when (next.status) {
                    "completed" -> ToolStatus.COMPLETED
                    "failed" -> ToolStatus.FAILED
                    else -> ToolStatus.RUNNING
                },
                result = next.result
            )
        )
    }

    private fun applyPlan(t: AcpTurn, update: JsonObject) {
        val entries = update.getAsJsonArray("entries") ?: return
        val text = entries.mapNotNull { e ->
            val o = e.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val mark = when (o.str("status")) { "completed" -> "[x]"; "in_progress" -> "[~]"; else -> "[ ]" }
            "$mark ${o.str("content") ?: ""}"
        }.joinToString("\n")
        t.put("plan", MessagePart.ToolCall("Plan", text, ToolStatus.COMPLETED, null))
    }

    private fun locationsText(update: JsonObject): String? =
        update.getAsJsonArray("locations")?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject?.str("path") }
            ?.takeIf { it.isNotEmpty() }?.joinToString("\n")

    private fun toolContentText(update: JsonObject): String? {
        val content = update.getAsJsonArray("content") ?: return null
        val text = content.mapNotNull { item ->
            val o = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            when (o.str("type")) {
                "content" -> o.getAsJsonObject("content")?.str("text")
                "diff" -> "Edit: ${o.str("path")}"
                else -> null
            }
        }.joinToString("\n")
        return text.takeIf { it.isNotBlank() }?.take(MAX_TEXT)
    }

    // ---- agent -> client requests ----------------------------------------------------------

    private suspend fun handleRequest(method: String, params: JsonObject?): JsonElement? {
        val p = params ?: throw AcpRpcException(-32602, "Missing params")
        return when (method) {
            "fs/read_text_file" -> handleRead(p)
            "fs/write_text_file" -> handleWrite(p)
            "session/request_permission" -> handlePermission(p)
            else -> throw AcpRpcException(-32601, "Method not supported by this client: $method")
        }
    }

    private fun relativeOrThrow(absolutePath: String): String =
        fileBridge.toRelativePath(absolutePath).getOrElse {
            throw AcpRpcException(-32602, it.message ?: "Invalid path: $absolutePath")
        }

    private fun handleRead(p: JsonObject): JsonElement {
        val rel = relativeOrThrow(p.str("path") ?: throw AcpRpcException(-32602, "path is required"))
        val full = fileBridge.readFile(rel).getOrElse {
            throw AcpRpcException(-32002, it.message ?: "Cannot read $rel")
        }
        lastRead[rel] = full

        val line = p.get("line")?.takeIf { it.isJsonPrimitive }?.asInt
        val limit = p.get("limit")?.takeIf { it.isJsonPrimitive }?.asInt
        val content = if (line == null && limit == null) full else {
            val sliced = full.lines().drop(((line ?: 1) - 1).coerceAtLeast(0))
            (if (limit != null) sliced.take(limit.coerceAtLeast(0)) else sliced).joinToString("\n")
        }
        return JsonObject().apply { addProperty("content", content) }
    }

    private suspend fun handleWrite(p: JsonObject): JsonElement {
        val rel = relativeOrThrow(p.str("path") ?: throw AcpRpcException(-32602, "path is required"))
        val content = p.str("content") ?: throw AcpRpcException(-32602, "content is required")
        val t = turn

        val old = fileBridge.readFile(rel).getOrNull() // null: new file (or unreadable)
        val diff = fileBridge.computeDiff(old.orEmpty(), content)

        val allowed = autoApply() || grantedPaths.remove(rel) || run {
            val request = ToolApprovalRequest(
                callId = "write-${UUID.randomUUID()}",
                toolName = "Write $rel",
                parameters = ToolParameters(targetFile = rel, diffPatch = renderDiff(diff)),
                reason = "The agent wants to modify this file"
            )
            requestUserDecision(t, request) == true
        }
        if (!allowed) throw AcpRpcException(-32000, "The user rejected this change")

        fileBridge.writeFile(rel, content, expectedOriginal = lastRead[rel]).exceptionOrNull()?.let { e ->
            throw AcpRpcException(
                -32000,
                if (e is PatchConflictException) "File changed since it was last read: $rel. Re-read it and try again."
                else e.message ?: "Write failed: $rel"
            )
        }
        lastRead[rel] = content
        t?.put("patch:${UUID.randomUUID()}", MessagePart.FilePatch(rel, old.orEmpty(), content, PatchStatus.APPLIED, diff))
        return JsonNull.INSTANCE
    }

    private suspend fun handlePermission(p: JsonObject): JsonElement {
        val toolCall = p.getAsJsonObject("toolCall") ?: throw AcpRpcException(-32602, "toolCall is required")
        val toolCallId = toolCall.str("toolCallId") ?: UUID.randomUUID().toString()
        val kind = toolCall.str("kind")
        val options = p.getAsJsonArray("options") ?: JsonArray()

        fun optionId(vararg kinds: String): String? = options.firstNotNullOfOrNull { e ->
            e.takeIf { it.isJsonObject }?.asJsonObject?.takeIf { it.str("kind") in kinds }?.str("optionId")
        }

        return when (kind) {
            "read", "search", "think" -> outcome(optionId("allow_once"))
            "execute" -> outcome(optionId("reject_once", "reject_always")) // no terminal capability
            else -> {
                val paths = permissionPaths(toolCall)
                val diffItem = toolCall.getAsJsonArray("content")
                    ?.firstOrNull { it.isJsonObject && it.asJsonObject.str("type") == "diff" }?.asJsonObject
                val diffText = diffItem?.let {
                    renderDiff(fileBridge.computeDiff(it.str("oldText").orEmpty(), it.str("newText").orEmpty()))
                }
                val request = ToolApprovalRequest(
                    callId = toolCallId,
                    toolName = toolCall.str("title") ?: "Tool call",
                    parameters = ToolParameters(
                        targetFile = paths.firstOrNull()?.let { fileBridge.toRelativePath(it).getOrNull() ?: it },
                        diffPatch = diffText
                    ),
                    reason = kind.orEmpty()
                )
                when (requestUserDecision(turn, request)) {
                    true -> {
                        paths.forEach { path -> fileBridge.toRelativePath(path).getOrNull()?.let(grantedPaths::add) }
                        // Only allow_once: never widen what the user actually approved.
                        outcome(optionId("allow_once"))
                    }
                    false -> outcome(optionId("reject_once", "reject_always"))
                    null -> outcome(null)
                }
            }
        }
    }

    private fun permissionPaths(toolCall: JsonObject): List<String> {
        val fromLocations = toolCall.getAsJsonArray("locations")?.mapNotNull {
            it.takeIf { e -> e.isJsonObject }?.asJsonObject?.str("path")
        }.orEmpty()
        val fromDiffs = toolCall.getAsJsonArray("content")?.mapNotNull {
            it.takeIf { e -> e.isJsonObject && e.asJsonObject.str("type") == "diff" }?.asJsonObject?.str("path")
        }.orEmpty()
        return (fromLocations + fromDiffs).distinct()
    }

    /** `optionId == null` means "no suitable option": answer with the cancelled outcome. */
    private fun outcome(optionId: String?): JsonObject = JsonObject().apply {
        add("outcome", JsonObject().apply {
            if (optionId != null) {
                addProperty("outcome", "selected")
                addProperty("optionId", optionId)
            } else {
                addProperty("outcome", "cancelled")
            }
        })
    }

    // ---- approvals -------------------------------------------------------------------------

    /** Shows an approval card and suspends until the user decides. true/false, or null if cancelled. */
    private suspend fun requestUserDecision(t: AcpTurn?, request: ToolApprovalRequest): Boolean? {
        if (t == null) return false // nobody to ask: refuse
        val key = "approval:${request.callId}"
        val deferred = CompletableDeferred<Boolean?>()
        pendingDecisions[request.callId] = deferred
        t.put(key, MessagePart.ToolApproval(request))
        val decision = try {
            deferred.await()
        } finally {
            pendingDecisions.remove(request.callId)
        }
        val status = if (decision == true) ToolApprovalStatus.APPROVED else ToolApprovalStatus.REJECTED
        t.put(key, MessagePart.ToolApproval(request.copy(status = status)))
        return decision
    }

    private fun cancelPendingDecisions() {
        pendingDecisions.values.forEach { it.complete(null) }
        pendingDecisions.clear()
    }

    // ---- helpers ---------------------------------------------------------------------------

    private fun textBlock(text: String) = JsonObject().apply {
        addProperty("type", "text")
        addProperty("text", text)
    }

    /** Context for the model only; OpenCode keeps it outside the user's visible message. */
    private fun assistantContextBlock(text: String) = JsonObject().apply {
        addProperty("type", "text")
        addProperty("text", text)
        add("annotations", JsonObject().apply {
            add("audience", JsonArray().apply { add("assistant") })
        })
    }

    private fun contextSummary(c: IdeProjectContext?): String? {
        if (c == null) return null
        val lines = buildList {
            c.projectName?.let { add("Project: $it") }
            c.activeFilePath?.let { add("Active file: $it") }
            c.cursorLine?.let { add("Cursor line: $it") }
            c.selectedText?.takeIf { it.isNotBlank() }?.let { add("Selected text:\n${it.take(MAX_TEXT)}") }
            c.lastBuildError?.takeIf { it.isNotBlank() }?.let { add("Last build error:\n${it.take(MAX_TEXT)}") }
        }
        return lines.takeIf { it.isNotEmpty() }?.joinToString("\n", prefix = "IDE context:\n")
    }

    /** Compact diff text: changed lines plus two lines of context around each change. */
    private fun renderDiff(lines: List<DiffLine>): String {
        val keep = BooleanArray(lines.size)
        lines.forEachIndexed { i, l ->
            if (l.type != DiffType.CONTEXT) {
                for (j in (i - 2).coerceAtLeast(0)..(i + 2).coerceAtMost(lines.lastIndex)) keep[j] = true
            }
        }
        val sb = StringBuilder()
        var shown = 0
        var skipped = false
        lines.forEachIndexed { i, l ->
            if (!keep[i]) { skipped = true; return@forEachIndexed }
            if (shown >= MAX_DIFF_LINES) { skipped = true; return@forEachIndexed }
            if (skipped && sb.isNotEmpty()) sb.append("...\n")
            skipped = false
            sb.append(
                when (l.type) {
                    DiffType.ADDITION -> "+ "
                    DiffType.DELETION -> "- "
                    DiffType.CONTEXT -> "  "
                }
            ).append(l.text).append('\n')
            shown++
        }
        if (shown >= MAX_DIFF_LINES) sb.append("... (diff truncated)\n")
        return sb.toString().trimEnd()
    }

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString
}
