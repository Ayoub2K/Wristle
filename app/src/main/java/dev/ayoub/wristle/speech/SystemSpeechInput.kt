package dev.ayoub.wristle.speech

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import dev.ayoub.wristle.R

/**
 * Speech input through the watch's own recognizer (on the Galaxy Watch 4 that is Google's).
 * Create it while the activity is being initialised: it registers an activity result callback.
 */
class SystemSpeechInput(
    private val activity: ComponentActivity,
    onText: (String) -> Unit,
) {
    private val launcher =
        activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            when {
                !text.isNullOrBlank() -> onText(text)
                // Swiping the recognizer away is a deliberate cancel, not an error.
                result.resultCode == Activity.RESULT_CANCELED -> Unit
                else -> showError(result.resultCode)
            }
        }

    fun listen() {
        val intent =
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, activity.getString(R.string.speech_prompt))
        try {
            launcher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.speech_error_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun showError(resultCode: Int) {
        val message =
            when (resultCode) {
                Activity.RESULT_OK, RecognizerIntent.RESULT_NO_MATCH -> activity.getString(R.string.speech_error_no_match)
                RecognizerIntent.RESULT_NETWORK_ERROR, RecognizerIntent.RESULT_SERVER_ERROR ->
                    activity.getString(R.string.speech_error_network)
                RecognizerIntent.RESULT_AUDIO_ERROR -> activity.getString(R.string.speech_error_audio)
                else -> activity.getString(R.string.speech_error_other, resultCode)
            }
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
    }
}
