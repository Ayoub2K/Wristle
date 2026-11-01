package dev.ayoub.wristle.data

import com.anthropic.core.jsonMapper
import com.fasterxml.jackson.databind.JsonNode
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class ChatStore(
    private val file: File,
    private val historyFile: File = File(file.parentFile, "history.json"),
) {
    fun load(): ChatSession =
        try {
            if (file.exists()) decode(jsonMapper().readTree(file)).interrupted() else ChatSession()
        } catch (e: Exception) {
            ChatSession()
        }

    fun save(session: ChatSession): Boolean = write(file, encode(session))

    fun loadHistory(): List<ChatSession> =
        try {
            if (historyFile.exists()) jsonMapper().readTree(historyFile).map(::decode) else emptyList()
        } catch (e: Exception) {
            emptyList()
        }

    fun saveHistory(history: List<ChatSession>): Boolean =
        write(historyFile, jsonMapper().createArrayNode().apply { history.forEach { add(encode(it)) } })

    private fun write(
        target: File,
        json: JsonNode,
    ): Boolean =
        try {
            val tmp = File(target.parentFile, "${target.name}.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(jsonMapper().writeValueAsBytes(json))
                out.fd.sync()
            }
            tmp.renameTo(target)
        } catch (e: IOException) {
            false
        }

    private fun encode(session: ChatSession): JsonNode {
        val root = jsonMapper().createObjectNode()
        root.put("status", session.status.name)
        root.put("seen", session.seen)
        root.put("startedAt", session.startedAt)
        session.failure?.let { failure ->
            root.putObject("failure").put("reason", failure.reason.name).put("detail", failure.detail)
        }
        val messages = root.putArray("messages")
        session.messages.forEach {
            messages
                .addObject()
                .put("id", it.id)
                .put("fromUser", it.fromUser)
                .put("text", it.text)
        }
        return root
    }

    private fun decode(root: JsonNode): ChatSession =
        ChatSession(
            messages =
                root.path("messages").map {
                    ChatMessage(it.path("id").asLong(), it.path("fromUser").asBoolean(), it.path("text").asText())
                },
            status = TurnStatus.entries.firstOrNull { it.name == root.path("status").asText() } ?: TurnStatus.IDLE,
            failure =
                root.get("failure")?.let {
                    FailureReason.entries
                        .firstOrNull { reason -> reason.name == it.path("reason").asText() }
                        ?.let { reason -> Failure(reason, it.get("detail")?.takeUnless { d -> d.isNull }?.asText()) }
                },
            seen = root.path("seen").asBoolean(true),
            startedAt = root.path("startedAt").asLong(0L),
        )
}
