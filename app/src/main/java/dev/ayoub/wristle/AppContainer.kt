package dev.ayoub.wristle

import android.content.Context
import dev.ayoub.wristle.data.ChatRepository
import dev.ayoub.wristle.data.ChatStore
import dev.ayoub.wristle.data.ClaudeRemoteDataSource
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
            settings = settingsRepository,
            store = ChatStore(File(appContext.noBackupFilesDir, "chat.json")),
            remote = ClaudeRemoteDataSource(),
            scope = appScope,
            onRequestStarted = { ChatRequestService.start(appContext) },
        )
}
