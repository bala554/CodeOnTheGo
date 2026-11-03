/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 */
package com.itsaky.androidide.actions.editor

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal data class OpenCodeChatSession(
	val id: String,
	val title: String,
	val updatedAt: Long,
)

internal data class OpenCodeChatModel(
	val providerId: String,
	val providerName: String,
	val id: String,
	val name: String,
)

internal data class OpenCodeChatProvider(
	val id: String,
	val name: String,
	val models: List<OpenCodeChatModel>,
)

internal data class OpenCodeModelCatalog(
	val providers: List<OpenCodeChatProvider>,
	val defaultModels: Map<String, String>,
)

internal data class OpenCodeChatMessage(
	val role: String,
	val text: String,
)

internal data class OpenCodeQuestionOption(
	val label: String,
	val description: String,
)

internal data class OpenCodeQuestionPrompt(
	val header: String,
	val text: String,
	val options: List<OpenCodeQuestionOption>,
	val multiple: Boolean,
)

internal data class OpenCodeQuestionRequest(
	val id: String,
	val sessionId: String,
	val questions: List<OpenCodeQuestionPrompt>,
)

/** HTTP client for the local OpenCode server API; no remote host or provider settings are stored here. */
internal class OpenCodeChatClient(
	private val baseUrl: String = "http://127.0.0.1:4098",
) {
	suspend fun listModelCatalog(): OpenCodeModelCatalog = withContext(Dispatchers.IO) {
		val payload = request("GET", "/provider") as? JSONObject
			?: throw IOException("OpenCode returned an invalid provider response")
		parseOpenCodeModelCatalog(payload)
	}

	suspend fun listSessions(limit: Int = 30): List<OpenCodeChatSession> = withContext(Dispatchers.IO) {
		val payload = request("GET", "/session?limit=$limit")
		val items = payload as? JSONArray ?: throw IOException("OpenCode returned an invalid sessions response")
		buildList {
			for (index in 0 until items.length()) {
				val item = items.optJSONObject(index) ?: continue
				val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
				val time = item.optJSONObject("time")
				add(
					OpenCodeChatSession(
						id = id,
						title = item.optString("title").ifBlank { "Untitled session" },
						updatedAt = time?.optLong("updated", 0L) ?: 0L,
					),
				)
			}
		}.sortedByDescending { it.updatedAt }
	}

	suspend fun createSession(): OpenCodeChatSession = withContext(Dispatchers.IO) {
		val payload = request("POST", "/session", JSONObject().put("title", "New session")) as? JSONObject
			?: throw IOException("OpenCode returned an invalid new-session response")
		val id = payload.optString("id").takeIf { it.isNotBlank() }
			?: throw IOException("OpenCode did not return a session ID")
		OpenCodeChatSession(
			id = id,
			title = payload.optString("title").ifBlank { "New session" },
			updatedAt = payload.optJSONObject("time")?.optLong("updated", 0L) ?: 0L,
		)
	}

	suspend fun listMessages(sessionId: String): List<OpenCodeChatMessage> = withContext(Dispatchers.IO) {
		val payload = request("GET", "/session/${pathSegment(sessionId)}/message")
		val items = payload as? JSONArray ?: throw IOException("OpenCode returned an invalid messages response")
		buildList {
			for (index in 0 until items.length()) {
				val item = items.optJSONObject(index) ?: continue
				val info = item.optJSONObject("info") ?: continue
				val role = info.optString("role")
				if (role != "user" && role != "assistant") continue
				val parts = item.optJSONArray("parts") ?: JSONArray()
				val text = buildString {
					for (partIndex in 0 until parts.length()) {
						val part = parts.optJSONObject(partIndex) ?: continue
						when (part.optString("type")) {
							"text" -> part.optString("text").takeIf { it.isNotBlank() }?.let {
								if (isNotEmpty()) append("\n")
								append(it)
							}
							"tool" -> {
								val tool = part.optString("tool").ifBlank { "tool" }
								val state = part.optJSONObject("state")
								val status = state?.optString("status").orEmpty()
								if (isNotEmpty()) append("\n")
								append("Tool: ").append(tool)
								if (status.isNotBlank()) append(" (").append(status).append(")")
							}
						}
					}
				}.trim()
				if (text.isNotBlank()) add(OpenCodeChatMessage(role, text))
			}
		}
	}

	suspend fun sendMessage(
		sessionId: String,
		text: String,
		agent: String,
		model: OpenCodeChatModel? = null,
	): List<OpenCodeChatMessage> = withContext(Dispatchers.IO) {
		val body = JSONObject()
			.put("agent", agent)
			.put("parts", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
			.apply {
				model?.let {
					put("model", JSONObject().put("providerID", it.providerId).put("modelID", it.id))
				}
			}
		request(
			method = "POST",
			path = "/session/${pathSegment(sessionId)}/message",
			body = body,
			readTimeoutMs = MESSAGE_TIMEOUT_MS,
		)
		listMessages(sessionId)
	}

	suspend fun listQuestions(sessionId: String): List<OpenCodeQuestionRequest> = withContext(Dispatchers.IO) {
		val payload = request("GET", "/question?sessionID=${queryValue(sessionId)}")
		val items = payload as? JSONArray ?: throw IOException("OpenCode returned an invalid questions response")
		buildList {
			for (index in 0 until items.length()) {
				val item = items.optJSONObject(index) ?: continue
				val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
				val ownerSessionId = item.optString("sessionID").takeIf { it.isNotBlank() } ?: continue
				if (ownerSessionId != sessionId) continue
				val questions = item.optJSONArray("questions") ?: continue
				val prompts = buildList {
					for (questionIndex in 0 until questions.length()) {
						val question = questions.optJSONObject(questionIndex) ?: continue
						val optionsJson = question.optJSONArray("options") ?: JSONArray()
						val options = buildList {
							for (optionIndex in 0 until optionsJson.length()) {
								val option = optionsJson.optJSONObject(optionIndex) ?: continue
								val label = option.optString("label").takeIf { it.isNotBlank() } ?: continue
								add(OpenCodeQuestionOption(label, option.optString("description")))
							}
						}
						add(
							OpenCodeQuestionPrompt(
								header = question.optString("header"),
								text = question.optString("question"),
								options = options,
								multiple = question.optBoolean("multiple"),
							),
						)
					}
				}
				if (prompts.isNotEmpty()) add(OpenCodeQuestionRequest(id, ownerSessionId, prompts))
			}
		}
	}

	suspend fun replyToQuestion(questionRequest: OpenCodeQuestionRequest, answers: List<List<String>>) = withContext(Dispatchers.IO) {
		val body = JSONObject().put(
			"answers",
			JSONArray().apply {
				answers.forEach { answer -> put(JSONArray(answer)) }
			},
		)
		request(
			method = "POST",
			path = "/question/${pathSegment(questionRequest.id)}/reply?sessionID=${queryValue(questionRequest.sessionId)}",
			body = body,
		)
	}

	suspend fun rejectQuestion(questionRequest: OpenCodeQuestionRequest) = withContext(Dispatchers.IO) {
		request(
			method = "POST",
			path = "/question/${pathSegment(questionRequest.id)}/reject?sessionID=${queryValue(questionRequest.sessionId)}",
		)
	}

	private fun request(
		method: String,
		path: String,
		body: JSONObject? = null,
		readTimeoutMs: Int = DEFAULT_TIMEOUT_MS,
	): Any? {
		val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
			requestMethod = method
			connectTimeout = CONNECT_TIMEOUT_MS
			readTimeout = readTimeoutMs
			instanceFollowRedirects = false
			setRequestProperty("Accept", "application/json")
			if (body != null) {
				doOutput = true
				setRequestProperty("Content-Type", "application/json; charset=utf-8")
			}
		}
		try {
			if (body != null) connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
			val status = connection.responseCode
			val stream = if (status in 200..299) connection.inputStream else connection.errorStream
			val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
			if (status !in 200..299) {
				val detail = runCatching { JSONObject(response).optString("message") }.getOrNull().orEmpty()
				throw IOException(detail.ifBlank { "OpenCode request failed (HTTP $status)" })
			}
			if (response.isBlank()) return null
			return when (response.trim().firstOrNull()) {
				'{' -> JSONObject(response)
				'[' -> JSONArray(response)
				else -> response
			}
		} finally {
			connection.disconnect()
		}
	}

	private fun pathSegment(value: String): String = Uri.encode(value)
	private fun queryValue(value: String): String = Uri.encode(value)

	private companion object {
		const val CONNECT_TIMEOUT_MS = 4_000
		const val DEFAULT_TIMEOUT_MS = 15_000
		const val MESSAGE_TIMEOUT_MS = 10 * 60 * 1_000
	}
}

internal fun parseOpenCodeModelCatalog(payload: JSONObject): OpenCodeModelCatalog {
	val providerItems = payload.optJSONArray("all") ?: payload.optJSONArray("providers") ?: JSONArray()
	val connectedItems = payload.optJSONArray("connected")
	val connected = connectedItems?.let { items ->
		buildSet {
			for (index in 0 until items.length()) items.optString(index).takeIf { it.isNotBlank() }?.let(::add)
		}
	}
	val defaults = mutableMapOf<String, String>()
	payload.optJSONObject("default")?.let { values ->
		val providerId = values.optString("providerID")
		val modelId = values.optString("modelID")
		if (providerId.isNotBlank() && modelId.isNotBlank()) defaults[providerId] = modelId
		for (key in values.keys()) {
			val value = values.optString(key)
			if (value.isNotBlank()) defaults.putIfAbsent(key, value)
		}
	}

	val providers = buildList {
		for (index in 0 until providerItems.length()) {
			val item = providerItems.optJSONObject(index) ?: continue
			val providerId = item.optString("id").takeIf { it.isNotBlank() } ?: continue
			if (connected != null && providerId !in connected) continue
			val modelItems = item.optJSONObject("models") ?: continue
			val models = buildList {
				for (modelId in modelItems.keys()) {
					val model = modelItems.optJSONObject(modelId) ?: continue
					if (model.has("enabled") && !model.optBoolean("enabled", true)) continue
					val id = model.optString("id").takeIf { it.isNotBlank() } ?: modelId
					val providerName = item.optString("name").ifBlank { providerId }
					add(
						OpenCodeChatModel(
							providerId = providerId,
							providerName = providerName,
							id = id,
							name = model.optString("name").ifBlank { id },
						),
					)
				}
			}.sortedBy { it.name.lowercase() }
			if (models.isNotEmpty()) {
				add(
					OpenCodeChatProvider(
						id = providerId,
						name = item.optString("name").ifBlank { providerId },
						models = models,
					),
				)
			}
		}
	}
	return OpenCodeModelCatalog(providers.sortedBy { it.name.lowercase() }, defaults)
}
