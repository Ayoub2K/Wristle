package dev.ayoub.wristle

import android.content.Context
import dev.ayoub.wristle.chat.ClaudeApiBackend
import dev.ayoub.wristle.data.ChatRepository
import dev.ayoub.wristle.data.ChatStore
import dev.ayoub.wristle.data.SecretCipher
import dev.ayoub.wristle.data.SettingsRepository
import dev.ayoub.wristle.service.ChatRequestService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

class AppContainer(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settingsRepository = SettingsRepository(appContext, SecretCipher())

    val chatRepository =
        ChatRepository(
            store = ChatStore(File(appContext.noBackupFilesDir, "chat.json")),
            // The phase 2 computer bridge plugs in here as another ChatBackend.
            backend = ClaudeApiBackend(settingsRepository),
            scope = appScope,
            onRequestStarted = { ChatRequestService.start(appContext) },
        )
}
