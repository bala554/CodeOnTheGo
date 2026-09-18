package com.itsaky.androidide.opencode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class OpenCodeChatViewModel(
	private val repository: OpenCodeChatRepository,
) : ViewModel() {
	private val _uiState = MutableStateFlow<OpenCodeChatUiState>(OpenCodeChatUiState.Ready())
	val uiState: StateFlow<OpenCodeChatUiState> = _uiState.asStateFlow()
	private val messages = mutableListOf<OpenCodeChatMessage>()

	fun sendMessage(text: String) {
		val prompt = text.trim()
		if (prompt.isEmpty()) return
		messages += OpenCodeChatMessage(content = prompt, isUser = true)
		_uiState.value = OpenCodeChatUiState.Ready(messages.toList(), waitingForResponse = true)
		viewModelScope.launch {
			try {
				messages += OpenCodeChatMessage(content = repository.sendMessage(prompt), isUser = false)
				_uiState.value = OpenCodeChatUiState.Ready(messages.toList(), waitingForResponse = false)
			} catch (error: Exception) {
				_uiState.value = OpenCodeChatUiState.Error(error.message ?: "OpenCode request failed")
			}
		}
	}

	fun clearChat() {
		messages.clear()
		_uiState.value = OpenCodeChatUiState.Ready()
	}
}
