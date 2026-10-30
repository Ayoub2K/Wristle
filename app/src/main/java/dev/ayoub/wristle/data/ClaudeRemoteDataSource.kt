package dev.ayoub.wristle.data

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.core.Timeout
import com.anthropic.core.http.StreamResponse
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.RawMessageStreamEvent
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.WebSearchTool20250305
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.atomic.AtomicReference
import kotlin.jvm.optionals.getOrNull

class ClaudeRemoteDataSource {
    private var client: AnthropicClient? = null
    private var clientKey: String? = null

    suspend fun stream(
        settings: AppSettings,
        history: List<ChatMessage>,
        onDelta: (String) -> Unit,
    ): String =
        withContext(Dispatchers.IO) {
            val model = SettingsRepository.MODELS.firstOrNull { it.id == settings.model }
            val system = "$SYSTEM_PROMPT The current local time is ${LocalDateTime.now().format(NOW_FORMAT)}."
            val builder =
                MessageCreateParams
                    .builder()
                    .model(settings.model)
                    .maxTokens(MAX_TOKENS)
                    .system(system)
                    .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(settings.effort)).build())
            builder.addTool(WebSearchTool20250305.builder().maxUses(3L).build())
            history.forEach { if (it.fromUser) builder.addUserMessage(it.text) else builder.addAssistantMessage(it.text) }
            if (model?.supportsFallbacks == true) {
                builder.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01")
                builder.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            }

            val client = client(settings.apiKey)
            var message = streamOnce(client, builder.build(), onDelta)
            // Server-side tools (web search) may pause a long turn; resume it by sending the partial reply back.
            repeat(MAX_PAUSE_ROUNDS) {
                if (message.stopReason().getOrNull() != StopReason.PAUSE_TURN) return@withContext finalText(message)
                builder.addMessage(message)
                ensureActive()
                message = streamOnce(client, builder.build(), onDelta)
            }
            finalText(message)
        }

    @Synchronized
    private fun client(apiKey: String): AnthropicClient {
        val existing = client
        if (existing != null && clientKey == apiKey) return existing
        existing?.close()
        return AnthropicOkHttpClient
            .builder()
            .apiKey(apiKey)
            .timeout(TIMEOUT)
            .build()
            .also {
                client = it
                clientKey = apiKey
            }
    }

    private suspend fun streamOnce(
        client: AnthropicClient,
        params: MessageCreateParams,
        onDelta: (String) -> Unit,
    ): Message =
        coroutineScope {
            val response = AtomicReference<StreamResponse<RawMessageStreamEvent>?>()
            val closer =
                launch {
                    try {
                        awaitCancellation()
                    } finally {
                        response.get()?.close()
                    }
                }
            try {
                val accumulator = StreamAccumulator()
                client.messages().createStreaming(params).also(response::set).use { stream ->
                    ensureActive()
                    stream.stream().forEach { event ->
                        ensureActive()
                        accumulator.accept(event)
                        event.contentBlockDelta().flatMap { it.delta().text() }.ifPresent { onDelta(it.text()) }
                    }
                }
                accumulator.message()
            } finally {
                closer.cancel()
            }
        }

    private fun finalText(message: Message): String = message.content().mapNotNull { it.text().getOrNull()?.text() }.joinToString("")

    private companion object {
        const val MAX_TOKENS = 2048L
        const val MAX_PAUSE_ROUNDS = 8

        val TIMEOUT: Timeout =
            Timeout
                .builder()
                .connect(Duration.ofSeconds(10))
                .read(Duration.ofSeconds(60))
                .write(Duration.ofSeconds(30))
                .request(Duration.ofSeconds(90))
                .build()

        const val SYSTEM_PROMPT =
            "You are a voice assistant on a smartwatch. Keep answers short, usually 1–3 sentences, " +
                "with no markdown, links, lists or tables: the reply is read aloud and shown on a small round screen. " +
                "Answer in the user's language. For recent events, weather, prices or other current data, use web search."

        val NOW_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.FULL, FormatStyle.SHORT)
    }
}
