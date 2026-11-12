package dev.ayoub.wristle

import android.Manifest
import android.app.RemoteInput
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.input.RemoteInputIntentHelper
import dev.ayoub.wristle.speech.InAppSpeechInput
import dev.ayoub.wristle.speech.ListeningState
import dev.ayoub.wristle.speech.ReplySpeaker
import dev.ayoub.wristle.speech.SpeechFailure
import dev.ayoub.wristle.speech.SystemSpeechInput
import dev.ayoub.wristle.ui.Destination
import dev.ayoub.wristle.ui.NavigationRequest
import dev.ayoub.wristle.ui.WristleApp
import dev.ayoub.wristle.ui.chat.ChatViewModel
import dev.ayoub.wristle.ui.settings.SettingsViewModel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val container get() = (application as WristleApplication).container
    private val chatViewModel: ChatViewModel by viewModels { ChatViewModel.factory(container) }
    private val settingsViewModel: SettingsViewModel by viewModels { SettingsViewModel.factory(container) }
    private val speaker by lazy { ReplySpeaker(this) }
    private var navigationRequest by mutableStateOf<NavigationRequest?>(null)

    // Fallback when in-app recognition isn't available or the microphone permission is refused.
    private val speechInput = SystemSpeechInput(this) { speechTarget(it) }
    private val inAppSpeech by lazy { InAppSpeechInput(this, onText = { speechTarget(it) }, onFailure = ::onSpeechFailure) }

    // Where recognised speech goes: a chat question by default, or an instruction for a Code session.
    private var speechTarget: (String) -> Unit = { chatViewModel.send(it) }

    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) inAppSpeech.start() else speechInput.listen()
        }

    private val keyInputLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val key =
                result.data
                    ?.let(RemoteInput::getResultsFromIntent)
                    ?.getCharSequence(KEY_INPUT)
                    ?.toString()
            if (!key.isNullOrBlank()) settingsViewModel.setApiKey(key)
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { listen() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(speaker)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                if (container.settingsRepository.settings.value.speakReplies) speaker.warmUp()
                launch { chatViewModel.replyProgress.collect { speaker.follow(it.id, it.text, it.done) } }
                // Leaving the screen on keeps the activity started, so the reply is fully received and read out.
                combine(chatViewModel.busy, speaker.speaking, inAppSpeech.state) { busy, speaking, listening ->
                    busy || speaking || listening !is ListeningState.Idle
                }.collect { keepOn ->
                    if (keepOn) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            }
        }
        setContent {
            WristleApp(
                chatViewModel = chatViewModel,
                settingsViewModel = settingsViewModel,
                relayTestRepository = container.relayTestRepository,
                pairingRepository = container.pairingRepository,
                codeRepository = container.codeRepository,
                onDictateCode = { project -> listen { container.codeRepository.start(project, it) } },
                navigationRequest = navigationRequest,
                listeningState = inAppSpeech.state,
                onFinishListening = inAppSpeech::finish,
                onCancelListening = inAppSpeech::cancel,
                onOpenChat = ::openChat,
                onMic = { listen() },
                onNewChat = {
                    chatViewModel.newChat()
                    listen()
                },
                onEditKey = ::editApiKey,
            )
        }
        if (savedInstanceState == null) handleLaunch(intent)
    }

    override fun onStop() {
        super.onStop()
        // Never keep the microphone open in the background.
        inAppSpeech.cancel()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunch(intent)
    }

    private fun handleLaunch(intent: Intent?) {
        val prompt = if (BuildConfig.DEBUG) intent?.getStringExtra(EXTRA_PROMPT) else null
        // The Home key (assistant) and the tile go straight to listening; the app list shows the start screen.
        val listenNow =
            intent?.action == Intent.ACTION_ASSIST ||
                intent?.action == Intent.ACTION_VOICE_COMMAND ||
                intent?.getBooleanExtra(EXTRA_LISTEN, false) == true ||
                !prompt.isNullOrBlank()
        when {
            !settingsViewModel.hasApiKey -> navigate(Destination.KEY_SETUP)
            !prompt.isNullOrBlank() -> {
                navigate(Destination.CHAT)
                chatViewModel.newChat()
                chatViewModel.send(prompt)
            }
            listenNow -> openChat()
            // From the app list: show an unseen or still-running answer, otherwise the start screen.
            !chatViewModel.shouldStartFreshChat -> navigate(Destination.CHAT)
            else -> navigate(Destination.HOME)
        }
    }

    /** Chat button, tile and Home key: always a new question; the previous chat goes to the history. */
    private fun openChat() {
        if (!settingsViewModel.hasApiKey) return navigate(Destination.KEY_SETUP)
        navigate(Destination.CHAT)
        chatViewModel.newChat()
        if (shouldAskNotificationPermission()) {
            container.settingsRepository.markNotificationPermissionAsked()
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            listen()
        }
    }

    private fun shouldAskNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !container.settingsRepository.settings.value.notificationPermissionAsked &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED

    private fun navigate(destination: Destination) {
        navigationRequest = NavigationRequest(destination, (navigationRequest?.id ?: 0) + 1)
    }

    private fun listen(target: (String) -> Unit = { chatViewModel.send(it) }) {
        speechTarget = target
        speaker.stop()
        when {
            !inAppSpeech.available -> speechInput.listen()
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ->
                inAppSpeech.start()
            else -> micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun onSpeechFailure(failure: SpeechFailure) {
        val message =
            when (failure) {
                SpeechFailure.NO_PERMISSION -> return speechInput.listen()
                SpeechFailure.NO_MATCH -> R.string.speech_error_no_match
                SpeechFailure.NETWORK -> R.string.speech_error_network
                SpeechFailure.AUDIO -> R.string.speech_error_audio
                SpeechFailure.OTHER -> R.string.speech_error_generic
            }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun editApiKey() {
        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(
            intent,
            listOf(RemoteInput.Builder(KEY_INPUT).setLabel(getString(R.string.settings_api_key)).build()),
        )
        try {
            keyInputLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.key_setup_no_keyboard, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val EXTRA_PROMPT = "prompt"
        const val EXTRA_LISTEN = "listen"
        private const val KEY_INPUT = "api_key"
    }
}
