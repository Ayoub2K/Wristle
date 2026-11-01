package dev.ayoub.wristle.ui

import android.content.Context
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.ayoub.wristle.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Reads a streaming reply aloud sentence by sentence, so speech starts before the reply is complete. */
class ReplySpeaker(
    context: Context,
) : DefaultLifecycleObserver {
    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(AudioManager::class.java)
    private var engine: TextToSpeech? = null
    private var ready = false
    private val pending = mutableListOf<String>()
    private val queued = AtomicInteger(0)
    private var utteranceCount = 0L
    private val speakingState = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = speakingState.asStateFlow()

    // Null means the system default engine; set once the default turns out not to speak the device language.
    private var engineName: String? = null

    private var replyId: Long? = null
    private var spokenUpTo = 0

    fun warmUp() {
        if (engine == null) connect()
    }

    fun follow(
        id: Long,
        text: String,
        done: Boolean,
    ) {
        if (id != replyId) {
            replyId = id
            // A reply that is already finished when first seen was not live (e.g. a restored chat): don't read it out.
            spokenUpTo = if (done) Int.MAX_VALUE else 0
            if (!done && audio?.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
                Toast.makeText(appContext, R.string.tts_volume_muted, Toast.LENGTH_SHORT).show()
            }
        }
        if (spokenUpTo >= text.length) return
        val end = if (done) text.length else speakableEnd(text, spokenUpTo) ?: return
        val chunk = text.substring(spokenUpTo, end).replace(MARKUP, "").trim()
        spokenUpTo = end
        if (chunk.isNotEmpty()) enqueue(chunk)
    }

    fun stop() {
        engine?.stop()
        pending.clear()
        spokenUpTo = Int.MAX_VALUE
        markIdle()
    }

    override fun onStop(owner: LifecycleOwner) = release()

    private fun enqueue(chunk: String) {
        val current = engine
        if (current != null && ready) {
            speakNow(current, chunk)
            return
        }
        pending += chunk
        if (current == null) connect()
    }

    private fun speakNow(
        tts: TextToSpeech,
        chunk: String,
    ) {
        queued.incrementAndGet()
        speakingState.value = true
        tts.speak(chunk, TextToSpeech.QUEUE_ADD, null, "reply-${utteranceCount++}")
    }

    private fun connect() {
        var created: TextToSpeech? = null
        created = TextToSpeech(appContext, { status -> if (engine === created) onInit(status) }, engineName)
        engine = created
    }

    private fun onInit(status: Int) {
        val current = engine
        if (status != TextToSpeech.SUCCESS || current == null) {
            release()
            return
        }
        val locale = Locale.getDefault()
        // Samsung's engine on the Galaxy Watch 4 has no Dutch voice and stays silent, so fall back to Google's.
        if (current.isLanguageAvailable(locale) < TextToSpeech.LANG_AVAILABLE &&
            engineName == null &&
            current.defaultEngine != GOOGLE_ENGINE &&
            current.engines.any { it.name == GOOGLE_ENGINE }
        ) {
            val waiting = pending.toList()
            release()
            engineName = GOOGLE_ENGINE
            pending += waiting
            connect()
            return
        }
        current.language = locale
        current.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) = utteranceFinished()

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = utteranceFinished()
            },
        )
        ready = true
        pending.forEach { speakNow(current, it) }
        pending.clear()
    }

    private fun utteranceFinished() {
        if (queued.decrementAndGet() <= 0) markIdle()
    }

    private fun markIdle() {
        queued.set(0)
        speakingState.value = false
    }

    private fun release() {
        engine?.shutdown()
        engine = null
        ready = false
        pending.clear()
        markIdle()
    }

    private companion object {
        const val GOOGLE_ENGINE = "com.google.android.tts"
        val MARKUP = Regex("[*_#`>]")
    }
}

/**
 * End index (exclusive) of the last complete sentence in [text] after [from], or null if none is complete yet.
 * A sentence is complete when its terminator is followed by whitespace, so "3.5" or "e.g." mid-word don't split.
 */
internal fun speakableEnd(
    text: String,
    from: Int,
): Int? {
    for (i in text.length - 2 downTo from) {
        if (text[i] in SENTENCE_END && text[i + 1].isWhitespace()) return i + 1
    }
    return null
}

private const val SENTENCE_END = ".!?…\n"
