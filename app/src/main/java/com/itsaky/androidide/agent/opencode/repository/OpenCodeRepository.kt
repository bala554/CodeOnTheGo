package com.itsaky.androidide.agent.opencode.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.itsaky.androidide.agent.opencode.bridge.OpenCodeFileBridge
import com.itsaky.androidide.agent.opencode.model.IdeProjectContext
import com.itsaky.androidide.agent.opencode.model.MessagePart
import com.itsaky.androidide.agent.opencode.model.MessageRole
import com.itsaky.androidide.agent.opencode.model.OpenCodeMessage
import com.itsaky.androidide.agent.opencode.model.OpenCodeServerConfig
import com.itsaky.androidide.agent.opencode.model.OpenCodeSession
import com.itsaky.androidide.agent.opencode.model.PatchStatus
import com.itsaky.androidide.agent.opencode.model.ServerConnectionStatus
import com.itsaky.androidide.agent.opencode.network.OpenCodeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Coordinates OpenCode server communication, session state, and file operations.
 */
class OpenCodeRepository(
    context: Context,
    val fileBridge: OpenCodeFileBridge,
    private val client: OpenCodeClient,
    private val appScope: CoroutineScope,
    val connectionService: com.itsaky.androidide.agent.opencode.service.OpenCodeConnectionService? = null,
    val agentRegistry: com.itsaky.androidide.agent.opencode.registry.AgentRegistry? = null
) {
    companion object {
        private const val TAG = "OpenCodeRepository"
        private const val PREFS_NAME = "opencode_settings"
        private const val KEY_HOST = "server_host"
        private const val KEY_PORT = "server_port"
        private const val KEY_TLS = "use_tls"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_AGENT = "active_agent"
        private const val KEY_MODEL = "active_model"
        private const val KEY_AUTO_APPLY = "auto_apply"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _serverConfig = MutableStateFlow(loadConfig())
    val serverConfig: StateFlow<OpenCodeServerConfig> = _serverConfig.asStateFlow()

    private val _connectionStatus = MutableStateFlow<ServerConnectionStatus>(ServerConnectionStatus.Disconnected)
    val connectionStatus: StateFlow<ServerConnectionStatus> = _connectionStatus.asStateFlow()

    private val _currentSession = MutableStateFlow<OpenCodeSession?>(null)
    val currentSession: StateFlow<OpenCodeSession?> = _currentSession.asStateFlow()

    private val _messages = MutableStateFlow<List<OpenCodeMessage>>(emptyList())
    val messages: StateFlow<List<OpenCodeMessage>> = _messages.asStateFlow()

    private val _rolledBackMessages = MutableStateFlow<List<OpenCodeMessage>>(emptyList())
    val rolledBackMessages: StateFlow<List<OpenCodeMessage>> = _rolledBackMessages.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    val registry: com.itsaky.androidide.agent.opencode.registry.AgentRegistry =
        agentRegistry ?: com.itsaky.androidide.agent.opencode.registry.DefaultAgentRegistry(context, appScope)

    val processManager: com.itsaky.androidide.agent.opencode.service.OpenCodeProcessManager =
        com.itsaky.androidide.agent.opencode.service.OpenCodeProcessManager(context, appScope)

    val manifest: AgentServerManifest = registry.getAgentManifest("opencode-core") ?: processManager.manifest
    val processState: StateFlow<ProcessState> = processManager.processState

    init {
        // Sync registry agent state with connection status
        appScope.launch {
            registry.agentStates.collect { statesMap ->
                val agentState = statesMap[manifest.id] ?: ProcessState.Available
                when (agentState) {
                    is ProcessState.Running -> {
                        _connectionStatus.value = ServerConnectionStatus.Connected("OpenCode v${manifest.version} (http://127.0.0.1:${agentState.port})")
                        _serverConfig.value = _serverConfig.value.copy(host = "127.0.0.1", port = agentState.port)
                    }
                    is ProcessState.Stopped, is ProcessState.Available -> {
                        if (_connectionStatus.value is ServerConnectionStatus.Connected && _serverConfig.value.host == "127.0.0.1") {
                            _connectionStatus.value = ServerConnectionStatus.Disconnected
                        }
                    }
                    is ProcessState.Error -> {
                        _connectionStatus.value = ServerConnectionStatus.Error(agentState.message, agentState.cause)
                    }
                    else -> {}
                }
            }
        }

        // Sync process manager state with connection status
        appScope.launch {
            processManager.processState.collect { pState ->
                when (pState) {
                    is ProcessState.Running -> {
                        _connectionStatus.value = ServerConnectionStatus.Connected("OpenCode v1.18.31 (http://127.0.0.1:${pState.port})")
                        _serverConfig.value = _serverConfig.value.copy(host = "127.0.0.1", port = pState.port)
                    }
                    is ProcessState.Stopped, is ProcessState.Available -> {
                        if (_connectionStatus.value is ServerConnectionStatus.Connected && _serverConfig.value.host == "127.0.0.1") {
                            _connectionStatus.value = ServerConnectionStatus.Disconnected
                        }
                    }
                    is ProcessState.Error -> {
                        _connectionStatus.value = ServerConnectionStatus.Error(pState.message, pState.cause)
                    }
                    else -> {}
                }
            }
        }
        // If connectionService is available, listen to its status updates
        connectionService?.let { service ->
            appScope.launch {
                service.connectionStatus.collect { status ->
                    _connectionStatus.value = status
                }
            }
        }

        // Test initial connectivity
        appScope.launch(Dispatchers.IO) {
            checkConnection()
        }
    }

    private fun loadConfig(): OpenCodeServerConfig {
        return OpenCodeServerConfig(
            host = prefs.getString(KEY_HOST, "127.0.0.1") ?: "127.0.0.1",
            port = prefs.getInt(KEY_PORT, 4098),
            useTls = prefs.getBoolean(KEY_TLS, false),
            authToken = prefs.getString(KEY_AUTH_TOKEN, "") ?: "",
            activeAgent = prefs.getString(KEY_AGENT, "plan") ?: "plan",
            activeModel = prefs.getString(KEY_MODEL, "MiMo-V2.6-Flash Free") ?: "MiMo-V2.6-Flash Free",
            autoApplyChanges = prefs.getBoolean(KEY_AUTO_APPLY, false)
        )
    }

    fun updateServerConfig(config: OpenCodeServerConfig) {
        _serverConfig.value = config
        prefs.edit()
            .putString(KEY_HOST, config.host)
            .putInt(KEY_PORT, config.port)
            .putBoolean(KEY_TLS, config.useTls)
            .putString(KEY_AUTH_TOKEN, config.authToken)
            .putString(KEY_AGENT, config.activeAgent)
            .putString(KEY_MODEL, config.activeModel)
            .putBoolean(KEY_AUTO_APPLY, config.autoApplyChanges)
            .apply()

        appScope.launch(Dispatchers.IO) {
            checkConnection()
        }
    }

    suspend fun checkConnection(): ServerConnectionStatus {
        if (connectionService != null) {
            return connectionService.checkHealth()
        }

        _connectionStatus.value = ServerConnectionStatus.Connecting
        val config = _serverConfig.value
        val result = client.checkServerConnection(config)

        val status = result.fold(
            onSuccess = { msg -> ServerConnectionStatus.Connected(msg) },
            onFailure = { err -> ServerConnectionStatus.Error(err.localizedMessage ?: "Failed to connect", err) }
        )
        _connectionStatus.value = status
        return status
    }

    suspend fun authenticateToken(token: String): Result<Boolean> {
        return Result.success(true)
    }

    suspend fun startNewSession(title: String? = null): OpenCodeSession {
        val config = _serverConfig.value
        val sessionResult = client.createSession(config, title)
        val session = sessionResult.getOrElse {
            OpenCodeSession(id = UUID.randomUUID().toString(), title = title ?: "New Session")
        }
        _currentSession.value = session
        _messages.value = emptyList()
        return session
    }

    suspend fun sendMessage(prompt: String, context: IdeProjectContext?): Result<String> {
        val session = _currentSession.value ?: startNewSession()
        val config = _serverConfig.value

        // Add user message
        val userMsgId = UUID.randomUUID().toString()
        val userMsg = OpenCodeMessage(
            id = userMsgId,
            role = MessageRole.USER,
            parts = listOf(MessagePart.Text(prompt))
        )
        _messages.value = _messages.value + userMsg

        // Add placeholder assistant message
        val assistantMsgId = UUID.randomUUID().toString()
        val initialAssistantMsg = OpenCodeMessage(
            id = assistantMsgId,
            role = MessageRole.ASSISTANT,
            parts = listOf(MessagePart.Text("")),
            isStreaming = true
        )
        _messages.value = _messages.value + initialAssistantMsg
        _isGenerating.value = true

        val textAccumulator = StringBuilder()
        val partsAccumulator = mutableListOf<MessagePart>()

        val result = client.sendPrompt(
            config = config,
            sessionId = session.id,
            prompt = prompt,
            context = context,
            onToken = { token ->
                textAccumulator.append(token)
                updateAssistantMessage(assistantMsgId, textAccumulator.toString(), partsAccumulator)
            },
            onReasoning = { reasoning ->
                partsAccumulator.removeAll { it is MessagePart.Reasoning }
                partsAccumulator.add(0, MessagePart.Reasoning(reasoning))
                updateAssistantMessage(assistantMsgId, textAccumulator.toString(), partsAccumulator)
            },
            onPatch = { patch ->
                partsAccumulator.add(patch)
                updateAssistantMessage(assistantMsgId, textAccumulator.toString(), partsAccumulator)

                if (config.autoApplyChanges) {
                    fileBridge.applyPatch(patch)
                }
            },
            onTool = { tool ->
                partsAccumulator.add(tool)
                updateAssistantMessage(assistantMsgId, textAccumulator.toString(), partsAccumulator)
            }
        )

        _isGenerating.value = false

        // Finalize assistant message
        _messages.value = _messages.value.map { msg ->
            if (msg.id == assistantMsgId) {
                msg.copy(isStreaming = false)
            } else msg
        }

        return result
    }

    private fun updateAssistantMessage(messageId: String, text: String, extraParts: List<MessagePart>) {
        val currentList = _messages.value
        _messages.value = currentList.map { msg ->
            if (msg.id == messageId) {
                val combinedParts = mutableListOf<MessagePart>()
                combinedParts.addAll(extraParts)
                if (text.isNotEmpty()) {
                    combinedParts.add(MessagePart.Text(text))
                }
                msg.copy(parts = combinedParts)
            } else msg
        }
    }

    fun cancelOngoingRequest() {
        client.cancelStreaming()
        _isGenerating.value = false
    }

    /**
     * Approves and writes a proposed patch to the project.
     */
    fun applyPatch(messageId: String, patch: MessagePart.FilePatch): Result<Unit> {
        val writeResult = fileBridge.applyPatch(patch)
        if (writeResult.isSuccess) {
            // Update patch status in message
            _messages.value = _messages.value.map { msg ->
                if (msg.id == messageId) {
                    val updatedParts = msg.parts.map { part ->
                        if (part is MessagePart.FilePatch && part.relativeFilePath == patch.relativeFilePath) {
                            part.copy(status = PatchStatus.APPLIED)
                        } else part
                    }
                    msg.copy(parts = updatedParts)
                } else msg
            }
        }
        return writeResult
    }

    /**
     * Discards a proposed patch.
     */
    fun rejectPatch(messageId: String, patch: MessagePart.FilePatch) {
        _messages.value = _messages.value.map { msg ->
            if (msg.id == messageId) {
                val updatedParts = msg.parts.map { part ->
                    if (part is MessagePart.FilePatch && part.relativeFilePath == patch.relativeFilePath) {
                        part.copy(status = PatchStatus.REJECTED)
                    } else part
                }
                msg.copy(parts = updatedParts)
            } else msg
        }
    }

    fun clearMessages() {
        _messages.value = emptyList()
        _rolledBackMessages.value = emptyList()
    }

    /**
     * Reverses the conversation back to a specific message so the user can edit it
     * and restart the conversation from that point.
     * Messages after (and including) this turn are placed into [_rolledBackMessages].
     * Returns the text of the message being edited.
     */
    fun rollbackToMessage(messageId: String): String? {
        val current = _messages.value
        val targetIndex = current.indexOfFirst { it.id == messageId }
        if (targetIndex == -1) return null

        val targetMessage = current[targetIndex]
        val originalPrompt = targetMessage.plainText

        val rolled = current.subList(targetIndex, current.size).toList()
        _rolledBackMessages.value = rolled
        _messages.value = current.subList(0, targetIndex).toList()

        return originalPrompt
    }

    /**
     * Restores previously rolled back messages back to the active conversation.
     */
    fun restoreRolledBackMessages() {
        val rolled = _rolledBackMessages.value
        if (rolled.isNotEmpty()) {
            _messages.value = _messages.value + rolled
            _rolledBackMessages.value = emptyList()
        }
    }

    /**
     * Clears rolled back messages history.
     */
    fun clearRolledBackMessages() {
        _rolledBackMessages.value = emptyList()
    }

    /**
     * Installs the OpenCode v1.18.31 self-contained binary into the local sandbox.
     */
    suspend fun installAgent(onProgress: ((Float, String) -> Unit)? = null): Result<Unit> {
        val result = registry.installAgent(manifest.id, onProgress)
        processManager.checkInstallationAndStatus()
        return result
    }

    /**
     * Spawns the local OpenCode server on localhost (127.0.0.1:4098) with automated health polling.
     */
    suspend fun startLocalServer(port: Int = 4098): Result<Int> {
        val spawnResult = registry.spawnAgent(manifest.id, port)
        if (spawnResult.isSuccess) {
            val assignedPort = spawnResult.getOrNull()?.port ?: port
            _serverConfig.value = _serverConfig.value.copy(host = "127.0.0.1", port = assignedPort)
            checkConnection()
            return Result.success(assignedPort)
        }
        val result = processManager.startServer(port)
        if (result.isSuccess) {
            val assignedPort = result.getOrDefault(port)
            _serverConfig.value = _serverConfig.value.copy(host = "127.0.0.1", port = assignedPort)
            checkConnection()
        }
        return result
    }

    /**
     * Stops the local OpenCode server process with SIGTERM/SIGKILL escalation.
     */
    suspend fun stopLocalServer() {
        registry.stopAgent(manifest.id)
        processManager.stopServer()
        _connectionStatus.value = ServerConnectionStatus.Disconnected
    }

    /**
     * Uninstalls the agent binary from the device sandbox.
     */
    suspend fun uninstallAgent() {
        registry.uninstallAgent(manifest.id)
        processManager.uninstallAgent()
        _connectionStatus.value = ServerConnectionStatus.Disconnected
    }

    /**
     * Handles human-in-the-loop tool approvals (e.g. file mutations or terminal commands).
     */
    fun respondToToolApproval(messageId: String, callId: String, approved: Boolean, userModifiedPatch: String? = null) {
        _messages.value = _messages.value.map { msg ->
            if (msg.id == messageId) {
                val updatedParts = msg.parts.map { part ->
                    if (part is com.itsaky.androidide.agent.opencode.model.MessagePart.ToolApproval && part.request.callId == callId) {
                        val newStatus = if (approved) com.itsaky.androidide.agent.opencode.model.ToolApprovalStatus.APPROVED else com.itsaky.androidide.agent.opencode.model.ToolApprovalStatus.REJECTED
                        part.copy(request = part.request.copy(status = newStatus))
                    } else part
                }
                msg.copy(parts = updatedParts)
            } else msg
        }

        if (approved) {
            val targetMsg = _messages.value.firstOrNull { it.id == messageId }
            val approvalPart = targetMsg?.parts?.filterIsInstance<com.itsaky.androidide.agent.opencode.model.MessagePart.ToolApproval>()?.firstOrNull { it.request.callId == callId }
            val patch = approvalPart?.request?.parameters?.diffPatch
            val targetFile = approvalPart?.request?.parameters?.targetFile
            if (!patch.isNullOrBlank() && !targetFile.isNullOrBlank()) {
                val filePatch = com.itsaky.androidide.agent.opencode.model.MessagePart.FilePatch(
                    relativeFilePath = targetFile,
                    originalContent = "",
                    proposedContent = userModifiedPatch ?: patch,
                    status = com.itsaky.androidide.agent.opencode.model.PatchStatus.APPLIED
                )
                fileBridge.applyPatch(filePatch)
            }
        }
    }
}

