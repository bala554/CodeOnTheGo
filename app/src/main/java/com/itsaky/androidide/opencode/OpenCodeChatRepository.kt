package com.itsaky.androidide.opencode

class OpenCodeChatRepository(
	private val client: OpenCodeClient,
) {
	suspend fun sendMessage(prompt: String): String = client.sendMessage(prompt)
}
