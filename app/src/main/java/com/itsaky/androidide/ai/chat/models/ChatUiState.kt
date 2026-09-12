/*
 *  This file is part of Code On The Go.
 *
 *  Code On The Go is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  Code On The Go is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with Code On The Go.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.itsaky.androidide.ai.chat.models

/**
 * UI state for the chat screen
 */
sealed interface ChatUiState {
    
    /**
     * Initial/idle state
     */
    data object Initializing : ChatUiState
    
    /**
     * Chat is ready with messages
     */
    data class Ready(
        val messages: List<ChatMessageUiModel> = emptyList(),
        val sessionId: String = "",
        val projectPath: String = "",
        val isInputEnabled: Boolean = true,
        val inputText: String = "",
        val editingMessageId: String? = null
    ) : ChatUiState
    
    /**
     * Currently processing user message
     */
    data class Loading(
        val messages: List<ChatMessageUiModel> = emptyList(),
        val sessionId: String = ""
    ) : ChatUiState
    
    /**
     * Error state
     */
    data class Error(
        val message: String = "An error occurred",
        val throwable: Throwable? = null,
        val canRetry: Boolean = true
    ) : ChatUiState
    
    /**
     * Server connection error
     */
    data class ServerError(
        val serverUrl: String = "",
        val canRetry: Boolean = true
    ) : ChatUiState
}

/**
 * UI events from the chat screen
 */
sealed class ChatUiEvent {
    data class SendMessage(val content: String) : ChatUiEvent()
    data class EditMessage(val messageId: String, val newContent: String) : ChatUiEvent()
    data class StartEditingMessage(val messageId: String) : ChatUiEvent()
    data object CancelEditing : ChatUiEvent()
    data class CopyToClipboard(val text: String) : ChatUiEvent()
    data object LoadSession : ChatUiEvent()
    data object RetryConnection : ChatUiEvent()
    data class SelectSession(val sessionId: String) : ChatUiEvent()
    data object CreateNewSession : ChatUiEvent()
}

/**
 * One-shot effects to be handled by the UI
 */
sealed class ChatUiEffect {
    data class ShowError(val message: String) : ChatUiEffect()
    data class ShowToast(val message: String) : ChatUiEffect()
    data object MessageCopied : ChatUiEffect()
    data object MessageEdited : ChatUiEffect()
    data class NavigateToFile(val filePath: String, val line: Int = 0) : ChatUiEffect()
}
