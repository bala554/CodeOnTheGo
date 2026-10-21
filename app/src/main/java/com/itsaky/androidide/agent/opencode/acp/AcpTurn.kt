package com.itsaky.androidide.agent.opencode.acp

import com.itsaky.androidide.agent.opencode.model.MessagePart

/**
 * Collects everything the agent streams during one prompt turn.
 *
 * Updates can arrive from the transport's reader thread (session/update) and from request
 * coroutines (permission prompts, file writes) at the same time. Every callback is invoked
 * while holding [lock], so the UI layer always sees a consistent, serialized stream.
 *
 * @param onToken a chunk of assistant text
 * @param onParts a full snapshot of all non-text parts (reasoning, tool calls, approvals,
 *   applied patches) in stable order; replaces the previous snapshot
 */
class AcpTurn(
    private val onToken: (String) -> Unit,
    private val onParts: (List<MessagePart>) -> Unit
) {
    private val lock = Any()
    private val parts = LinkedHashMap<String, MessagePart>()
    private val reasoning = StringBuilder()
    private val text = StringBuilder()

    val fullText: String get() = synchronized(lock) { text.toString() }

    fun appendText(chunk: String) = synchronized(lock) {
        text.append(chunk)
        onToken(chunk)
    }

    fun appendReasoning(chunk: String) = synchronized(lock) {
        reasoning.append(chunk)
        emitLocked()
    }

    /** Inserts or replaces the part stored under [key] (insertion order is kept on replace). */
    fun put(key: String, part: MessagePart) = synchronized(lock) {
        parts[key] = part
        emitLocked()
    }

    private fun emitLocked() {
        val snapshot = ArrayList<MessagePart>(parts.size + 1)
        if (reasoning.isNotEmpty()) snapshot.add(MessagePart.Reasoning(reasoning.toString()))
        snapshot.addAll(parts.values)
        onParts(snapshot)
    }
}
