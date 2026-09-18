package com.itsaky.androidide.opencode

import java.util.UUID

public data class OpenCodeChatMessage(
	val id: String = UUID.randomUUID().toString(),
	val content: String,
	val isUser: Boolean,
)

public sealed interface OpenCodeChatUiState {
	public data object Idle : OpenCodeChatUiState

	public data class Ready(
		val messages: List<OpenCodeChatMessage> = emptyList(),
		val waitingForResponse: Boolean = false,
	) : OpenCodeChatUiState

	public data class Error(
		val message: String,
	) : OpenCodeChatUiState
}
