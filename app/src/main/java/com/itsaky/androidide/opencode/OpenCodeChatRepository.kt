package com.itsaky.androidide.opencode

class OpenCodeChatRepository(
	private val client: OpenCodeClient = OpenCodeHttpClient(),
) {
	suspend fun sendMessage(prompt: String): String = client.sendMessage(prompt)
}
