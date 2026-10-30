package dev.ayoub.wristle.assist

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import dev.ayoub.wristle.MainActivity

/** Registered as the system digital assistant; the side-button long-press lands here. */
class WristleVoiceInteractionService : VoiceInteractionService()

class WristleSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = WristleSession(this)
}

/** No overlay UI: the session just opens the chat activity with a fresh conversation. */
class WristleSession(
    context: Context,
) : VoiceInteractionSession(context) {
    override fun onShow(
        args: Bundle?,
        showFlags: Int,
    ) {
        super.onShow(args, showFlags)
        startAssistantActivity(Intent(context, MainActivity::class.java))
        hide()
    }
}

/** Stub required by the voice interaction spec; speech input goes through RecognizerIntent. */
class WristleRecognitionService : RecognitionService() {
    override fun onStartListening(
        recognizerIntent: Intent?,
        listener: Callback?,
    ) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onCancel(listener: Callback?) {}

    override fun onStopListening(listener: Callback?) {}
}
