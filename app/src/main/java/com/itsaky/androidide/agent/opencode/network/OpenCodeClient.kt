package com.itsaky.androidide.agent.opencode.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.itsaky.androidide.agent.opencode.bridge.OpenCodeFileBridge
import com.itsaky.androidide.agent.opencode.model.IdeProjectContext
import com.itsaky.androidide.agent.opencode.model.MessagePart
import com.itsaky.androidide.agent.opencode.model.OpenCodeServerConfig
import com.itsaky.androidide.agent.opencode.model.OpenCodeSession
import com.itsaky.androidide.agent.opencode.model.PatchStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Handles communication with the OpenCode HTTP and SSE server (`opencode serve`).
 */
class OpenCodeClient(
    private val fileBridge: OpenCodeFileBridge
) {
    companion object {
        private const val TAG = "OpenCodeClient"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val gson = Gson()
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var activeCall: Call? = null

    /**
     * Checks whether the OpenCode server is reachable and responsive.
     */
    suspend fun checkServerConnection(config: OpenCodeServerConfig): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("${config.baseUrl}/doc")
                .apply {
                    if (config.authToken.isNotBlank()) {
                        addHeader("Authorization", "Bearer ${config.authToken}")
                    }
                }
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful || response.code in 200..399) {
                Result.success("OpenCode Server reachable at ${config.baseUrl}")
            } else {
                // Fallback test to /session
                val sessionReq = Request.Builder()
                    .url("${config.baseUrl}/session")
                    .get()
                    .build()
                val sessionResp = httpClient.newCall(sessionReq).execute()
                if (sessionResp.isSuccessful || sessionResp.code in 200..399) {
                    Result.success("OpenCode Server online at ${config.baseUrl}")
                } else {
                    Result.failure(IOException("Server returned HTTP ${sessionResp.code}"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to OpenCode server at ${config.baseUrl}", e)
            Result.failure(e)
        }
    }

    /**
     * Creates a new session on the OpenCode server per ACP (Agent Communication Protocol).
     */
    suspend fun createSession(
        config: OpenCodeServerConfig,
        title: String? = null,
        workspaceRoot: String? = null
    ): Result<OpenCodeSession> = withContext(Dispatchers.IO) {
        try {
            val bodyJson = JsonObject().apply {
                if (title != null) addProperty("title", title)
                if (workspaceRoot != null) addProperty("workspaceRoot", workspaceRoot)
                val caps = JsonArray().apply {
                    add("file_read")
                    add("file_write")
                    add("terminal_exec")
                }
                add("grantedCapabilities", caps)
                val envFlags = JsonObject().apply {
                    addProperty("OPENCODE_VERSION", "1.18.31")
                }
                add("environmentFlags", envFlags)
            }

            var request = Request.Builder()
                .url("${config.baseUrl}/session/create")
                .apply {
                    if (config.authToken.isNotBlank()) {
                        addHeader("Authorization", "Bearer ${config.authToken}")
                    }
                }
                .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            var response: Response? = null
            try {
                response = httpClient.newCall(request).execute()
            } catch (_: Exception) {
                // Fallback to legacy /session
            }

            if (response == null || !response.isSuccessful) {
                request = Request.Builder()
                    .url("${config.baseUrl}/session")
                    .apply {
                        if (config.authToken.isNotBlank()) {
                            addHeader("Authorization", "Bearer ${config.authToken}")
                        }
                    }
                    .post(bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                response = httpClient.newCall(request).execute()
            }

            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("Failed to create session: HTTP ${response.code}"))
            }

            val responseBody = response.body?.string() ?: "{}"
            val json = JsonParser.parseString(responseBody).asJsonObject
            val id = if (json.has("sessionId")) json.get("sessionId").asString
                     else if (json.has("id")) json.get("id").asString
                     else UUID.randomUUID().toString()
            val sessionTitle = if (json.has("title")) json.get("title").asString else (title ?: "Session $id")

            Result.success(OpenCodeSession(id = id, title = sessionTitle))
        } catch (e: Exception) {
            Log.e(TAG, "Error creating session", e)
            Result.success(OpenCodeSession(id = UUID.randomUUID().toString(), title = title ?: "Session"))
        }
    }

    /**
     * Sends a prompt to the OpenCode agent and streams responses via SSE or streaming HTTP.
     */
    suspend fun sendPrompt(
        config: OpenCodeServerConfig,
        sessionId: String,
        prompt: String,
        context: IdeProjectContext?,
        onToken: (String) -> Unit,
        onReasoning: (String) -> Unit,
        onPatch: (MessagePart.FilePatch) -> Unit,
        onTool: (MessagePart.ToolCall) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Assemble enriched prompt with project context
            val fullPrompt = buildContextEnrichedPrompt(prompt, context)

            val payload = JsonObject().apply {
                addProperty("prompt", fullPrompt)
                addProperty("agent", config.activeAgent)
                if (config.activeModel.isNotBlank()) {
                    addProperty("model", config.activeModel)
                }
                if (context?.projectRootPath != null) {
                    addProperty("project_dir", context.projectRootPath)
                }
            }

            val request = Request.Builder()
                .url("${config.baseUrl}/session/$sessionId/prompt_async")
                .apply {
                    if (config.authToken.isNotBlank()) {
                        addHeader("Authorization", "Bearer ${config.authToken}")
                    }
                    addHeader("Accept", "text/event-stream, application/json")
                }
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val call = httpClient.newCall(request)
            activeCall = call

            var response: Response? = null
            try {
                response = call.execute()
            } catch (e: Exception) {
                // If async endpoint fails, try sync prompt endpoint
                val syncRequest = Request.Builder()
                    .url("${config.baseUrl}/session/$sessionId/prompt")
                    .apply {
                        if (config.authToken.isNotBlank()) {
                            addHeader("Authorization", "Bearer ${config.authToken}")
                        }
                        addHeader("Accept", "text/event-stream, application/json")
                    }
                    .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                response = httpClient.newCall(syncRequest).execute()
            }

            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("Server error: HTTP ${response.code} ${response.message}"))
            }

            val contentType = response.header("Content-Type", "") ?: ""
            val accumulatedText = StringBuilder()

            if (contentType.contains("text/event-stream")) {
                // Handle Server-Sent Events (SSE)
                val reader = BufferedReader(InputStreamReader(response.body!!.byteStream()))
                var line: String? = reader.readLine()

                while (line != null && coroutineContext.isActive) {
                    if (line.startsWith("data:")) {
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") break

                        try {
                            val eventObj = JsonParser.parseString(data).asJsonObject
                            handleSseEvent(eventObj, accumulatedText, onToken, onReasoning, onPatch, onTool)
                        } catch (e: Exception) {
                            // Raw text token in SSE data
                            accumulatedText.append(data)
                            onToken(data)
                        }
                    }
                    line = reader.readLine()
                }
            } else {
                // Handle standard JSON response
                val bodyString = response.body?.string() ?: ""
                try {
                    val jsonObj = JsonParser.parseString(bodyString).asJsonObject
                    if (jsonObj.has("parts")) {
                        parsePartsArray(jsonObj.getAsJsonArray("parts"), accumulatedText, onToken, onReasoning, onPatch, onTool)
                    } else if (jsonObj.has("content")) {
                        val text = jsonObj.get("content").asString
                        accumulatedText.append(text)
                        onToken(text)
                    } else if (jsonObj.has("response")) {
                        val text = jsonObj.get("response").asString
                        accumulatedText.append(text)
                        onToken(text)
                    } else {
                        accumulatedText.append(bodyString)
                        onToken(bodyString)
                    }
                } catch (e: Exception) {
                    accumulatedText.append(bodyString)
                    onToken(bodyString)
                }
            }

            // Inspect accumulated text for file modification markdown blocks
            extractFilePatchesFromText(accumulatedText.toString(), onPatch)

            Result.success(accumulatedText.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error during prompt execution", e)
            Result.failure(e)
        } finally {
            activeCall = null
        }
    }

    /**
     * Cancels any ongoing streaming request and signals ACP /session/cancel.
     */
    fun cancelStreaming(config: OpenCodeServerConfig? = null, sessionId: String? = null) {
        activeCall?.cancel()
        activeCall = null

        if (config != null && sessionId != null) {
            try {
                val payload = JsonObject().apply { addProperty("sessionId", sessionId) }
                val cancelReq = Request.Builder()
                    .url("${config.baseUrl}/session/cancel")
                    .apply {
                        if (config.authToken.isNotBlank()) {
                            addHeader("Authorization", "Bearer ${config.authToken}")
                        }
                    }
                    .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
                httpClient.newCall(cancelReq).enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: Call, e: IOException) {}
                    override fun onResponse(call: Call, response: Response) { response.close() }
                })
            } catch (_: Exception) {}
        }
    }

    /**
     * Transmits human-in-the-loop tool approval or rejection to the agent server.
     */
    suspend fun respondToToolApproval(
        config: OpenCodeServerConfig,
        sessionId: String,
        callId: String,
        approved: Boolean,
        userModifiedPatch: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val payload = JsonObject().apply {
                addProperty("sessionId", sessionId)
                addProperty("callId", callId)
                addProperty("approved", approved)
                if (userModifiedPatch != null) {
                    addProperty("userModifiedPatch", userModifiedPatch)
                }
            }
            val request = Request.Builder()
                .url("${config.baseUrl}/tool/respond")
                .apply {
                    if (config.authToken.isNotBlank()) {
                        addHeader("Authorization", "Bearer ${config.authToken}")
                    }
                }
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = httpClient.newCall(request).execute()
            response.close()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun handleSseEvent(
        event: JsonObject,
        accumulatedText: StringBuilder,
        onToken: (String) -> Unit,
        onReasoning: (String) -> Unit,
        onPatch: (MessagePart.FilePatch) -> Unit,
        onTool: (MessagePart.ToolCall) -> Unit
    ) {
        when (val type = event.get("type")?.asString) {
            "token", "text", "delta" -> {
                val token = event.get("content")?.asString ?: event.get("text")?.asString ?: ""
                accumulatedText.append(token)
                onToken(token)
            }
            "reasoning", "thought" -> {
                val thought = event.get("content")?.asString ?: ""
                onReasoning(thought)
            }
            "tool_call" -> {
                val name = event.get("name")?.asString ?: "tool"
                val args = event.get("arguments")?.asString ?: ""
                onTool(MessagePart.ToolCall(toolName = name, arguments = args))
            }
            "patch", "file_edit" -> {
                val filePath = event.get("file")?.asString ?: ""
                val proposed = event.get("content")?.asString ?: ""
                val original = fileBridge.readFile(filePath).getOrDefault("")
                val diff = fileBridge.computeDiff(original, proposed)
                onPatch(
                    MessagePart.FilePatch(
                        relativeFilePath = filePath,
                        originalContent = original,
                        proposedContent = proposed,
                        status = PatchStatus.PENDING,
                        diffLines = diff
                    )
                )
            }
            else -> {
                if (event.has("content")) {
                    val content = event.get("content").asString
                    accumulatedText.append(content)
                    onToken(content)
                }
            }
        }
    }

    private fun parsePartsArray(
        parts: JsonArray,
        accumulatedText: StringBuilder,
        onToken: (String) -> Unit,
        onReasoning: (String) -> Unit,
        onPatch: (MessagePart.FilePatch) -> Unit,
        onTool: (MessagePart.ToolCall) -> Unit
    ) {
        for (elem in parts) {
            if (!elem.isJsonObject) continue
            val partObj = elem.asJsonObject
            val type = partObj.get("type")?.asString ?: "text"

            when (type) {
                "text" -> {
                    val text = partObj.get("text")?.asString ?: ""
                    accumulatedText.append(text)
                    onToken(text)
                }
                "reasoning" -> {
                    val text = partObj.get("text")?.asString ?: ""
                    onReasoning(text)
                }
                "tool" -> {
                    val name = partObj.get("name")?.asString ?: ""
                    val args = partObj.get("args")?.asString ?: ""
                    onTool(MessagePart.ToolCall(toolName = name, arguments = args))
                }
                "patch" -> {
                    val file = partObj.get("path")?.asString ?: ""
                    val proposed = partObj.get("content")?.asString ?: ""
                    val original = fileBridge.readFile(file).getOrDefault("")
                    onPatch(
                        MessagePart.FilePatch(
                            relativeFilePath = file,
                            originalContent = original,
                            proposedContent = proposed,
                            status = PatchStatus.PENDING,
                            diffLines = fileBridge.computeDiff(original, proposed)
                        )
                    )
                }
            }
        }
    }

    /**
     * Inspects text for code blocks specifying target files (e.g. ```kotlin:app/src/... or ```diff)
     * so proposed changes can be turned into interactive diff cards.
     */
    private fun extractFilePatchesFromText(text: String, onPatch: (MessagePart.FilePatch) -> Unit) {
        val fileBlockRegex = Regex("```[a-zA-Z0-9_-]+:([a-zA-Z0-9_./-]+)\\n([\\s\\S]*?)```")
        for (match in fileBlockRegex.findAll(text)) {
            val relativePath = match.groupValues[1].trim()
            val proposedContent = match.groupValues[2]
            val original = fileBridge.readFile(relativePath).getOrDefault("")
            val diff = fileBridge.computeDiff(original, proposedContent)
            onPatch(
                MessagePart.FilePatch(
                    relativeFilePath = relativePath,
                    originalContent = original,
                    proposedContent = proposedContent,
                    status = PatchStatus.PENDING,
                    diffLines = diff
                )
            )
        }
    }

    private fun buildContextEnrichedPrompt(prompt: String, context: IdeProjectContext?): String {
        if (context == null) return prompt

        val builder = StringBuilder()
        builder.append(prompt).append("\n\n")
        builder.append("--- IDE CONTEXT ---").append("\n")

        if (!context.projectName.isNullOrBlank()) {
            builder.append("Project: ").append(context.projectName).append("\n")
        }
        if (!context.activeFileName.isNullOrBlank()) {
            builder.append("Active File: ").append(context.activeFileName).append("\n")
        }
        if (!context.selectedText.isNullOrBlank()) {
            builder.append("Selected Code:\n```\n").append(context.selectedText).append("\n```\n")
        }
        if (!context.lastBuildError.isNullOrBlank()) {
            builder.append("Latest Build/Diagnostic Error:\n```\n").append(context.lastBuildError).append("\n```\n")
        }
        builder.append("-------------------")
        return builder.toString()
    }
}
