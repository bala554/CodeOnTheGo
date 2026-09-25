package com.itsaky.androidide.agent.opencode.model

import java.io.File

/**
 * Configuration for connecting to an OpenCode server (`opencode serve`).
 */
data class OpenCodeServerConfig(
    val host: String = "127.0.0.1",
    val port: Int = 4098,
    val useTls: Boolean = false,
    val authToken: String = "",
    val activeAgent: String = "plan", // e.g. "build", "plan", "explore", "general"
    val activeModel: String = "MiMo-V2.6-Flash Free",
    val autoApplyChanges: Boolean = false
) {
    val baseUrl: String
        get() {
            val scheme = if (useTls) "https" else "http"
            val cleanHost = host.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
            return "$scheme://$cleanHost:$port"
        }
}

/**
 * Model specification supported by OpenCode and its free providers.
 */
data class OpenCodeProviderModel(
    val id: String,
    val name: String,
    val provider: String,
    val isFree: Boolean = true,
    val description: String = ""
)

/**
 * Catalog of verified free models offered through OpenCode's providers (Zen / Free tier, OpenRouter Free, Ollama).
 */
object OpenCodeFreeProviders {
    val MODELS: List<OpenCodeProviderModel> = listOf(
        // OpenCode Free / Zen built-in models
        OpenCodeProviderModel(
            id = "opencode/mimo-v2.6-flash-free",
            name = "MiMo-V2.6-Flash Free",
            provider = "OpenCode Free",
            description = "Fast reasoning & code planning (Default)"
        ),
        OpenCodeProviderModel(
            id = "opencode/deepseek-v3-free",
            name = "DeepSeek V3 Free",
            provider = "OpenCode Free",
            description = "High capability coding & refactoring"
        ),
        OpenCodeProviderModel(
            id = "opencode/qwen-2.5-coder-free",
            name = "Qwen 2.5 Coder Free",
            provider = "OpenCode Free",
            description = "Optimized for Android & Kotlin code"
        ),
        OpenCodeProviderModel(
            id = "opencode/minimax-m2.5-free",
            name = "Minimax M2.5 Free",
            provider = "OpenCode Free",
            description = "Fast conversational coding"
        ),
        OpenCodeProviderModel(
            id = "opencode/nemotron-3-super-free",
            name = "Nemotron 3 Super Free",
            provider = "OpenCode Free",
            description = "NVIDIA code intelligence"
        ),
        OpenCodeProviderModel(
            id = "opencode/ling-2.6-flash-free",
            name = "Ling 2.6 Flash Free",
            provider = "OpenCode Free",
            description = "Low-latency code completions"
        ),
        // OpenRouter Free Tier
        OpenCodeProviderModel(
            id = "openrouter/deepseek/deepseek-chat:free",
            name = "DeepSeek Chat Free",
            provider = "OpenRouter",
            description = "DeepSeek V3 via OpenRouter free tier"
        ),
        OpenCodeProviderModel(
            id = "openrouter/meta-llama/llama-3.3-70b-instruct:free",
            name = "Llama 3.3 70B Free",
            provider = "OpenRouter",
            description = "Meta Llama 3.3 70B Free"
        ),
        OpenCodeProviderModel(
            id = "openrouter/qwen/qwen-2.5-coder-32b-instruct:free",
            name = "Qwen 2.5 Coder 32B Free",
            provider = "OpenRouter",
            description = "Qwen Coder 32B Free"
        ),
        OpenCodeProviderModel(
            id = "openrouter/google/gemma-2-9b-it:free",
            name = "Gemma 2 9B Free",
            provider = "OpenRouter",
            description = "Google Gemma 2 Free"
        ),
        // Ollama Local Provider (Local server)
        OpenCodeProviderModel(
            id = "ollama/qwen2.5-coder:7b",
            name = "Qwen 2.5 Coder 7B (Ollama)",
            provider = "Ollama Local",
            description = "Local offline model via Ollama"
        ),
        OpenCodeProviderModel(
            id = "ollama/deepseek-coder-v2:16b",
            name = "DeepSeek Coder V2 (Ollama)",
            provider = "Ollama Local",
            description = "Local open weights coding model"
        )
    )
}

/**
 * Represents a conversation session with the OpenCode server.
 */
data class OpenCodeSession(
    val id: String,
    val title: String = "Session $id",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Message in an OpenCode session.
 */
data class OpenCodeMessage(
    val id: String,
    val role: MessageRole,
    val parts: List<MessagePart> = emptyList(),
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false
) {
    val plainText: String
        get() = parts.filterIsInstance<MessagePart.Text>().joinToString("\n") { it.content }

    val diffs: List<MessagePart.FilePatch>
        get() = parts.filterIsInstance<MessagePart.FilePatch>()
}

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}

/**
 * Structured content part in an OpenCode message.
 */
sealed interface MessagePart {
    data class Text(val content: String) : MessagePart
    data class Reasoning(val reasoningText: String) : MessagePart
    data class ToolCall(
        val toolName: String,
        val arguments: String,
        val status: ToolStatus = ToolStatus.RUNNING,
        val result: String? = null
    ) : MessagePart
    data class ToolApproval(
        val request: ToolApprovalRequest
    ) : MessagePart
    data class FilePatch(
        val relativeFilePath: String,
        val originalContent: String,
        val proposedContent: String,
        val status: PatchStatus = PatchStatus.PENDING,
        val diffLines: List<DiffLine> = emptyList()
    ) : MessagePart
    data class QuestionCard(
        val id: String = java.util.UUID.randomUUID().toString(),
        val questions: List<QuestionItem> = emptyList(),
        val questionIndexText: String = "1 of 1 questions",
        val title: String = "",
        val subtitle: String = "Select one answer",
        val options: List<QuestionOption> = emptyList(),
        val selectedOptionId: String? = null,
        val customAnswer: String = "",
        val isSubmitted: Boolean = false,
        val isDismissed: Boolean = false
    ) : MessagePart {
        fun getAllQuestions(): List<QuestionItem> {
            if (questions.isNotEmpty()) return questions
            return if (title.isNotBlank()) {
                listOf(
                    QuestionItem(
                        id = id,
                        title = title,
                        subtitle = subtitle,
                        options = options,
                        selectedOptionId = selectedOptionId,
                        customAnswer = customAnswer
                    )
                )
            } else emptyList()
        }
    }
}

data class QuestionItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val subtitle: String = "Select one answer",
    val options: List<QuestionOption>,
    val selectedOptionId: String? = null,
    val customAnswer: String = ""
)

data class QuestionOption(
    val id: String,
    val title: String,
    val description: String? = null,
    val isCustomInput: Boolean = false
)

enum class ToolStatus {
    RUNNING,
    COMPLETED,
    FAILED
}

enum class PatchStatus {
    PENDING,
    APPLIED,
    REJECTED
}

data class DiffLine(
    val type: DiffType,
    val text: String,
    val oldLineNumber: Int? = null,
    val newLineNumber: Int? = null
)

enum class DiffType {
    CONTEXT,
    ADDITION,
    DELETION
}

/**
 * Context provided from the active IDE state to OpenCode.
 */
data class IdeProjectContext(
    val projectName: String? = null,
    val projectRootPath: String? = null,
    val activeFilePath: String? = null,
    val activeFileName: String? = null,
    val selectedText: String? = null,
    val cursorLine: Int? = null,
    val lastBuildError: String? = null
)

/**
 * Connection status to the OpenCode server.
 */
sealed interface ServerConnectionStatus {
    data object Disconnected : ServerConnectionStatus
    data object Connecting : ServerConnectionStatus
    data class Connected(val serverVersion: String? = null) : ServerConnectionStatus
    data class Error(val message: String, val cause: Throwable? = null) : ServerConnectionStatus
}

/**
 * Server type convention for agent servers.
 */
enum class ServerType {
    BINARY,
    NODE_MODULE,
    DOCKER_CONTAINER
}

/**
 * Host capabilities requested by or granted to the agent server.
 */
enum class AgentCapability {
    FILE_READ,
    FILE_WRITE,
    TERMINAL_EXEC,
    GIT_OPERATIONS
}

/**
 * Platform-specific binary distribution package and checksum.
 */
data class BinaryDistribution(
    val url: String,
    val sha256: String,
    val executablePath: String
)

/**
 * An actively running agent server instance on localhost.
 */
data class RunningAgentInstance(
    val agentId: String,
    val manifest: AgentServerManifest,
    val host: String = "127.0.0.1",
    val port: Int,
    val pid: Long? = null,
    val startedAt: Long = System.currentTimeMillis(),
    val healthCheckEndpoint: String = "/health"
) {
    val baseUrl: String get() = "http://$host:$port"
}

/**
 * Manifest defining a self-contained local agent server (ACP specification).
 */
data class AgentServerManifest(
    val id: String = "opencode-core",
    val name: String = "OpenCode AI Engine",
    val version: String = "1.18.31",
    val description: String = "Autonomous local code intelligence server engine without external telemetry or API keys.",
    val serverType: ServerType = ServerType.BINARY,
    val defaultPort: Int = 4098,
    val dynamicPortAllowed: Boolean = true,
    val healthCheckEndpoint: String = "/health",
    val capabilities: List<AgentCapability> = listOf(
        AgentCapability.FILE_READ,
        AgentCapability.FILE_WRITE,
        AgentCapability.TERMINAL_EXEC
    ),
    val distribution: Map<String, BinaryDistribution> = emptyMap(),
    val spawnArguments: List<String> = listOf("serve", "--port", "\${PORT}", "--cors-allow-origin", "*"),
    val environmentVariables: Map<String, String> = mapOf(
        "OPENCODE_DISABLE_TELEMETRY" to "1",
        "OPENCODE_HOST" to "127.0.0.1"
    )
)

/**
 * State tracking for the local agent registry & process lifecycle.
 */
sealed interface ProcessState {
    data object Available : ProcessState
    data class Downloading(val progress: Float, val statusMessage: String) : ProcessState
    data object Stopped : ProcessState
    data class Starting(val statusMessage: String) : ProcessState
    data class Running(val port: Int, val pid: Long? = null, val host: String = "127.0.0.1") : ProcessState
    data class Error(val message: String, val cause: Throwable? = null) : ProcessState
}

/**
 * Human-in-the-loop tool approval request from OpenCode agent to the IDE host.
 */
data class ToolApprovalRequest(
    val callId: String = java.util.UUID.randomUUID().toString(),
    val toolName: String,
    val parameters: ToolParameters = ToolParameters(),
    val reason: String = "",
    val status: ToolApprovalStatus = ToolApprovalStatus.PENDING
)

data class ToolParameters(
    val targetFile: String? = null,
    val diffPatch: String? = null,
    val command: String? = null,
    val workingDirectory: String? = null
)

enum class ToolApprovalStatus {
    PENDING,
    APPROVED,
    REJECTED
}

data class ToolApprovalResponse(
    val callId: String,
    val approved: Boolean,
    val userModifiedPatch: String? = null,
    val rejectionReason: String? = null
)

/**
 * Agent Communication Protocol (ACP) stream payload types.
 */
enum class ACPStreamEventType {
    TOKEN,
    REASONING,
    TOOL_CALL,
    STATUS,
    ERROR,
    DONE
}

data class ACPStreamPayload(
    val eventId: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val type: ACPStreamEventType,
    val token: String? = null,
    val reasoning: String? = null,
    val toolCall: ToolApprovalRequest? = null,
    val statusText: String? = null,
    val error: String? = null
)

