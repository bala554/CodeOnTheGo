package com.itsaky.androidide.opencode

import android.net.Uri
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Minimal non-streaming OpenCode HTTP client. The server's /doc schema is authoritative. */
class OpenCodeHttpClient(
	private val baseUrl: String = DEFAULT_BASE_URL,
	private val connectTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
	private val readTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
	private val gson: Gson = Gson(),
) : OpenCodeClient {
	@Volatile
	private var sessionId: String? = null

	override suspend fun sendMessage(prompt: String): String {
		val id = sessionId ?: createSession().also { sessionId = it }
		val response = request(
			method = "POST",
			path = "/session/${Uri.encode(id)}/message",
			body = gson.toJson(mapOf("text" to prompt)),
		)
		return extractAssistantText(response)
	}

	private fun createSession(): String {
		val response = request("POST", "/session", "{}")
		val json = JsonParser.parseString(response).asJsonObject
		return json.stringOrNull("id") ?: throw IOException("OpenCode response did not contain a session id")
	}

	private fun request(method: String, path: String, body: String? = null): String {
		val connection = (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
			requestMethod = method
			connectTimeout = connectTimeoutMs
			readTimeout = readTimeoutMs
			setRequestProperty("Accept", "application/json")
			if (body != null) {
				doOutput = true
				setRequestProperty("Content-Type", "application/json")
			}
		}

		return try {
			if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
			val status = connection.responseCode
			val stream = if (status in 200..299) connection.inputStream else connection.errorStream
			val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
			if (status !in 200..299) throw IOException("OpenCode HTTP $status: ${text.take(500)}")
			text
		} finally {
			connection.disconnect()
		}
	}

	private fun extractAssistantText(payload: String): String {
		val json = JsonParser.parseString(payload)
		if (json.isJsonPrimitive) return json.asString
		if (!json.isJsonObject) return payload
		val objectJson = json.asJsonObject
		return sequenceOf("text", "content", "message", "response")
			.mapNotNull { objectJson.stringOrNull(it) }
			.firstOrNull()
			?: objectJson.getAsJsonArray("parts")?.joinToString("") { part ->
				part.asJsonObject.stringOrNull("text").orEmpty()
			}.orEmpty().ifBlank { payload }
	}

	private fun JsonObject.stringOrNull(name: String): String? =
		get(name)?.takeUnless { it.isJsonNull }?.takeIf { it.isJsonPrimitive }?.asString

	companion object {
		const val DEFAULT_BASE_URL = "http://127.0.0.1:4096"
		const val DEFAULT_TIMEOUT_MS = 30_000
	}
}

interface OpenCodeClient {
	suspend fun sendMessage(prompt: String): String
}
