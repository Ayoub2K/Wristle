package dev.ayoub.wristle.ui

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

class ReplySpeaker(
    context: Context,
) : DefaultLifecycleObserver {
    private val appContext = context.applicationContext
    private var engine: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    fun speak(text: String) {
        val clean = text.replace(MARKUP, "")
        val current = engine
        if (current != null && ready) {
            current.speak(clean, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
            return
        }
        pending = clean
        if (current == null) {
            var created: TextToSpeech? = null
            created = TextToSpeech(appContext) { status -> if (engine === created) onInit(status) }
            engine = created
        }
    }

    fun stop() {
        engine?.stop()
        pending = null
    }

    override fun onStop(owner: LifecycleOwner) = release()

    private fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            release()
            return
        }
        ready = true
        pending?.let { engine?.speak(it, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID) }
        pending = null
    }

    private fun release() {
        engine?.shutdown()
        engine = null
        ready = false
        pending = null
    }

    private companion object {
        const val UTTERANCE_ID = "reply"
        val MARKUP = Regex("[*_#`>]")
    }
}
