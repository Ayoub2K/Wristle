package dev.ayoub.wristle

import android.Manifest
import android.app.RemoteInput
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
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
import dev.ayoub.wristle.speech.ReplySpeaker
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

    private val speechInput = SystemSpeechInput(this) { chatViewModel.send(it) }

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
                combine(chatViewModel.busy, speaker.speaking) { busy, speaking -> busy || speaking }
                    .collect { keepOn ->
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
                navigationRequest = navigationRequest,
                onMic = ::listen,
                onNewChat = {
                    chatViewModel.newChat()
                    listen()
                },
                onEditKey = ::editApiKey,
            )
        }
        if (savedInstanceState == null) handleLaunch(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunch(intent)
    }

    private fun handleLaunch(intent: Intent?) {
        navigate(Destination.CHAT)
        if (!chatViewModel.shouldStartFreshChat) return
        chatViewModel.newChat()
        val prompt = if (BuildConfig.DEBUG) intent?.getStringExtra(EXTRA_PROMPT) else null
        when {
            !settingsViewModel.hasApiKey -> {
                navigate(Destination.SETTINGS)
                editApiKey()
            }
            !prompt.isNullOrBlank() -> chatViewModel.send(prompt)
            shouldAskNotificationPermission() -> {
                container.settingsRepository.markNotificationPermissionAsked()
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            else -> listen()
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

    private fun listen() {
        speaker.stop()
        speechInput.listen()
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
            navigate(Destination.SETTINGS)
        }
    }

    companion object {
        const val EXTRA_PROMPT = "prompt"
        private const val KEY_INPUT = "api_key"
    }
}
