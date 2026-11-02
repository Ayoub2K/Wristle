package dev.ayoub.wristle.speech

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

sealed interface ListeningState {
    data object Idle : ListeningState

    /** [level] is the input level normalised to 0..1; [partial] is the text recognised so far. */
    data class Listening(
        val level: Float = 0f,
        val partial: String = "",
    ) : ListeningState

    data object Processing : ListeningState
}

enum class SpeechFailure { NO_MATCH, NETWORK, AUDIO, NO_PERMISSION, OTHER }

/**
 * Speech input inside the app through [SpeechRecognizer], so there's no switch to the recognizer's own
 * activity (which can show a black screen for seconds on a cold start). Must be used on the main thread.
 */
class InAppSpeechInput(
    context: Context,
    private val onText: (String) -> Unit,
    private val onFailure: (SpeechFailure) -> Unit,
) {
    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private val mutableState = MutableStateFlow<ListeningState>(ListeningState.Idle)
    val state: StateFlow<ListeningState> = mutableState.asStateFlow()

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(appContext)

    fun start() {
        cancel()
        val created = create()
        recognizer = created
        created.setRecognitionListener(Listener(created))
        mutableState.value = ListeningState.Listening()
        created.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName),
        )
    }

    /** Stops listening and uses what was heard so far. */
    fun finish() {
        recognizer?.stopListening()
        if (mutableState.value is ListeningState.Listening) mutableState.value = ListeningState.Processing
    }

    /** Stops listening and throws away what was heard. */
    fun cancel() {
        recognizer?.destroy()
        recognizer = null
        mutableState.value = ListeningState.Idle
    }

    // Prefer Google's service explicitly: while Wristle is the digital assistant, its own stub recognizer must not be picked.
    private fun create(): SpeechRecognizer {
        val google = ComponentName(GOOGLE_PACKAGE, GOOGLE_SERVICE)
        val hasGoogle = runCatching { appContext.packageManager.getServiceInfo(google, 0) }.isSuccess
        return if (hasGoogle) {
            SpeechRecognizer.createSpeechRecognizer(appContext, google)
        } else {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        }
    }

    private inner class Listener(
        private val owner: SpeechRecognizer,
    ) : RecognitionListener {
        // Callbacks from a recognizer that was already replaced or cancelled are ignored.
        private val current get() = recognizer === owner

        override fun onRmsChanged(rmsdB: Float) {
            if (!current) return
            val level = ((rmsdB - MIN_RMS_DB) / (MAX_RMS_DB - MIN_RMS_DB)).coerceIn(0f, 1f)
            mutableState.update { if (it is ListeningState.Listening) it.copy(level = level) else it }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!current) return
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isNotBlank()) mutableState.update { if (it is ListeningState.Listening) it.copy(partial = text) else it }
        }

        override fun onEndOfSpeech() {
            if (current) mutableState.value = ListeningState.Processing
        }

        override fun onResults(results: Bundle?) {
            if (!current) return
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            cancel()
            if (text.isNullOrBlank()) onFailure(SpeechFailure.NO_MATCH) else onText(text)
        }

        override fun onError(error: Int) {
            if (!current) return
            cancel()
            onFailure(
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechFailure.NO_MATCH
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                        SpeechFailure.NETWORK
                    SpeechRecognizer.ERROR_AUDIO -> SpeechFailure.AUDIO
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechFailure.NO_PERMISSION
                    else -> SpeechFailure.OTHER
                },
            )
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit

        override fun onBeginningOfSpeech() = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEvent(
            eventType: Int,
            params: Bundle?,
        ) = Unit
    }

    private companion object {
        const val GOOGLE_PACKAGE = "com.google.android.tts"
        const val GOOGLE_SERVICE = "com.google.android.apps.speech.tts.googletts.service.GoogleTTSRecognitionService"

        // Typical range of onRmsChanged values; quieter is silence, louder is shouting.
        const val MIN_RMS_DB = -2f
        const val MAX_RMS_DB = 10f
    }
}
