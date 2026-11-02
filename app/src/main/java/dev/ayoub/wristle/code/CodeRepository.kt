package dev.ayoub.wristle.code

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.ayoub.wristle.relay.PairingCrypto
import dev.ayoub.wristle.relay.PairingRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class Project(
    val id: String,
    val name: String,
)

/** A Claude Code session on the computer, started from the watch. [id] is null until the bridge accepts it. */
data class CodeSession(
    val id: String?,
    val project: String,
    val prompt: String,
    val model: String? = null,
    val lines: List<String> = emptyList(),
    val running: Boolean = true,
    val ok: Boolean? = null,
    val summary: String? = null,
    val costUsd: Double? = null,
)

/** [projects] is null while the computer hasn't answered yet. */
data class CodeState(
    val projects: List<Project>? = null,
    val session: CodeSession? = null,
    val error: String? = null,
    val noAnswer: Boolean = false,
)

/**
 * Watch side of P2-M3: asks the paired bridge for its allowed projects, starts a session with a spoken
 * instruction and follows its progress. Everything travels through the encrypted mailbox.
 */
class CodeRepository(
    private val pairing: PairingRepository,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(CodeState())
    val state: StateFlow<CodeState> = mutableState.asStateFlow()
    private var answerTimeout: Job? = null

    init {
        scope.launch { pairing.messages.collect(::onMessage) }
    }

    fun refresh() {
        mutableState.update { it.copy(error = null, noAnswer = false) }
        send(PairingCrypto.message("projects"))
        answerTimeout?.cancel()
        answerTimeout =
            scope.launch {
                delay(ANSWER_TIMEOUT_MS)
                mutableState.update { if (it.projects == null) it.copy(noAnswer = true) else it }
            }
    }

    fun start(
        project: Project,
        prompt: String,
    ) {
        if (mutableState.value.session?.running == true) return
        mutableState.update { it.copy(session = CodeSession(id = null, project = project.name, prompt = prompt), error = null) }
        send(PairingCrypto.message("start").put("project", project.id).put("prompt", prompt))
    }

    fun stop() {
        val id = mutableState.value.session?.id ?: return
        send(PairingCrypto.message("stop").put("sessionId", id))
    }

    /** Clears a finished session so the project list shows again. */
    fun dismiss() {
        mutableState.update { if (it.session?.running == false) it.copy(session = null) else it }
    }

    private fun send(message: ObjectNode) {
        scope.launch {
            try {
                pairing.sendToBridge(message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    private fun onMessage(message: JsonNode) {
        when (message["type"].asText()) {
            "projects" -> {
                answerTimeout?.cancel()
                val projects = message["projects"]?.map { Project(it["id"].asText(), it["name"].asText()) }.orEmpty()
                mutableState.update { it.copy(projects = projects, noAnswer = false) }
            }
            "session" ->
                updateSession(message) { session ->
                    session.copy(
                        id = message["sessionId"].asText(),
                        model = message["model"]?.asText() ?: session.model,
                    )
                }
            "progress" ->
                updateSession(message) { session ->
                    val lines = session.lines + message["lines"]?.map { it.asText() }.orEmpty()
                    session.copy(lines = lines.takeLast(MAX_LINES))
                }
            "done" ->
                updateSession(message) { session ->
                    session.copy(
                        running = false,
                        ok = message["ok"]?.asBoolean() ?: false,
                        summary = message["summary"]?.asText(),
                        costUsd = message["costUsd"]?.takeIf { it.isNumber }?.asDouble(),
                    )
                }
            "error" ->
                mutableState.update {
                    // A start the bridge refused never became a session.
                    val session = it.session?.takeUnless { s -> s.id == null }
                    it.copy(error = message["message"]?.asText(), session = session)
                }
        }
    }

    private fun updateSession(
        message: JsonNode,
        change: (CodeSession) -> CodeSession,
    ) {
        val id = message["sessionId"]?.asText()
        mutableState.update { state ->
            val session = state.session ?: return@update state
            if (session.id != null && session.id != id) return@update state
            state.copy(session = change(session))
        }
    }

    private companion object {
        const val ANSWER_TIMEOUT_MS = 20_000L
        const val MAX_LINES = 6
    }
}
