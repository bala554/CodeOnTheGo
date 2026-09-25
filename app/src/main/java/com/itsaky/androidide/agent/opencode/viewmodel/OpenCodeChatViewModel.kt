package com.itsaky.androidide.agent.opencode.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsaky.androidide.agent.opencode.model.IdeProjectContext
import com.itsaky.androidide.agent.opencode.model.MessagePart
import com.itsaky.androidide.agent.opencode.model.OpenCodeMessage
import com.itsaky.androidide.agent.opencode.model.OpenCodeServerConfig
import com.itsaky.androidide.agent.opencode.model.OpenCodeSession
import com.itsaky.androidide.agent.opencode.model.ServerConnectionStatus
import com.itsaky.androidide.agent.opencode.repository.OpenCodeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

import com.itsaky.androidide.agent.opencode.model.AgentServerManifest
import com.itsaky.androidide.agent.opencode.model.ProcessState
import com.itsaky.androidide.agent.opencode.model.ToolApprovalStatus

data class OpenCodeChatUiState(
    val connectionStatus: ServerConnectionStatus = ServerConnectionStatus.Disconnected,
    val serverConfig: OpenCodeServerConfig = OpenCodeServerConfig(),
    val currentSession: OpenCodeSession? = null,
    val messages: List<OpenCodeMessage> = emptyList(),
    val rolledBackMessages: List<OpenCodeMessage> = emptyList(),
    val editingMessageId: String? = null,
    val isGenerating: Boolean = false,
    val ideContext: IdeProjectContext? = null,
    val errorMessage: String? = null,
    val processState: ProcessState = ProcessState.Available,
    val manifest: AgentServerManifest = AgentServerManifest()
)

class OpenCodeChatViewModel(
    private val repository: OpenCodeRepository
) : ViewModel() {

    private val _ideContext = MutableStateFlow<IdeProjectContext?>(null)
    val ideContext: StateFlow<IdeProjectContext?> = _ideContext.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _editingMessageId = MutableStateFlow<String?>(null)
    val editingMessageId: StateFlow<String?> = _editingMessageId.asStateFlow()

    private data class RepoState(
        val status: ServerConnectionStatus,
        val config: OpenCodeServerConfig,
        val session: OpenCodeSession?,
        val messages: List<OpenCodeMessage>,
        val rolledBack: List<OpenCodeMessage>,
        val processState: ProcessState
    )

    private val repoStateFlow = combine(
        repository.connectionStatus,
        repository.serverConfig,
        repository.currentSession,
        repository.messages,
        repository.rolledBackMessages,
        repository.processState
    ) { status, config, session, msgs, rolledBack, pState ->
        RepoState(status, config, session, msgs, rolledBack, pState)
    }

    val uiState: StateFlow<OpenCodeChatUiState> = combine(
        repoStateFlow,
        repository.isGenerating,
        _ideContext,
        _errorMessage,
        _editingMessageId
    ) { repo, generating, ctx, err, editingId ->
        OpenCodeChatUiState(
            connectionStatus = repo.status,
            serverConfig = repo.config,
            currentSession = repo.session,
            messages = repo.messages,
            rolledBackMessages = repo.rolledBack,
            editingMessageId = editingId,
            isGenerating = generating,
            ideContext = ctx,
            errorMessage = err,
            processState = repo.processState,
            manifest = repository.manifest
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = OpenCodeChatUiState()
    )

    init {
        // Refresh project context
        refreshProjectContext()
    }

    fun refreshProjectContext() {
        val root = repository.fileBridge.getProjectRoot()
        _ideContext.value = IdeProjectContext(
            projectName = root?.name,
            projectRootPath = root?.absolutePath
        )
    }

    fun setProjectContext(context: IdeProjectContext) {
        _ideContext.value = context
    }

    fun sendMessage(prompt: String) {
        if (prompt.isBlank() || uiState.value.isGenerating) return
        _editingMessageId.value = null

        viewModelScope.launch {
            _errorMessage.value = null
            val result = repository.sendMessage(prompt, _ideContext.value)
            result.onFailure { err ->
                _errorMessage.value = err.localizedMessage ?: "Failed to get response from OpenCode"
            }
        }
    }

    /**
     * Reverses the conversation to a chosen message and returns its content
     * so it can be loaded into the input editor for revision.
     */
    fun rollbackToMessage(messageId: String): String? {
        _editingMessageId.value = messageId
        return repository.rollbackToMessage(messageId)
    }

    /**
     * Restores the rolled back conversation turns if the user cancels restarting.
     */
    fun restoreRolledBackMessages() {
        _editingMessageId.value = null
        repository.restoreRolledBackMessages()
    }

    /**
     * Clears rolled back messages.
     */
    fun clearRolledBackMessages() {
        _editingMessageId.value = null
        repository.clearRolledBackMessages()
    }

    fun cancelEditing() {
        _editingMessageId.value = null
    }

    fun cancelGeneration() {
        repository.cancelOngoingRequest()
    }

    fun applyPatch(messageId: String, patch: MessagePart.FilePatch) {
        viewModelScope.launch {
            val result = repository.applyPatch(messageId, patch)
            result.onFailure { err ->
                _errorMessage.value = "Failed to apply patch to ${patch.relativeFilePath}: ${err.localizedMessage}"
            }
        }
    }

    fun rejectPatch(messageId: String, patch: MessagePart.FilePatch) {
        repository.rejectPatch(messageId, patch)
    }

    fun updateServerConfig(config: OpenCodeServerConfig) {
        repository.updateServerConfig(config)
    }

    fun reconnect() {
        viewModelScope.launch {
            repository.checkConnection()
        }
    }

    fun authenticateToken(token: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val result = repository.authenticateToken(token)
            onResult(result.isSuccess)
            if (result.isFailure) {
                _errorMessage.value = result.exceptionOrNull()?.localizedMessage ?: "Failed to authenticate token"
            }
        }
    }

    fun startNewSession(title: String? = null) {
        viewModelScope.launch {
            repository.startNewSession(title)
        }
    }

    fun clearChat() {
        repository.clearMessages()
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun installAgent(onProgress: ((Float, String) -> Unit)? = null) {
        viewModelScope.launch {
            _errorMessage.value = null
            val result = repository.installAgent(onProgress)
            if (result.isFailure) {
                _errorMessage.value = result.exceptionOrNull()?.localizedMessage ?: "Failed to install OpenCode agent"
            }
        }
    }

    fun startLocalServer(port: Int = 4098) {
        viewModelScope.launch {
            _errorMessage.value = null
            val result = repository.startLocalServer(port)
            if (result.isFailure) {
                _errorMessage.value = result.exceptionOrNull()?.localizedMessage ?: "Failed to start local OpenCode server"
            }
        }
    }

    fun stopLocalServer() {
        viewModelScope.launch {
            repository.stopLocalServer()
        }
    }

    fun uninstallAgent() {
        viewModelScope.launch {
            repository.uninstallAgent()
        }
    }

    fun respondToToolApproval(messageId: String, callId: String, approved: Boolean, userModifiedPatch: String? = null) {
        repository.respondToToolApproval(messageId, callId, approved, userModifiedPatch)
    }
}

