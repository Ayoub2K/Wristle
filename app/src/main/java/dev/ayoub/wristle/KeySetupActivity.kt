package dev.ayoub.wristle

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

class KeySetupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val settings = (application as WristleApplication).container.settingsRepository
        intent.getStringExtra(EXTRA_API_KEY)?.let(settings::setApiKey)
        Toast.makeText(applicationContext, R.string.keys_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        const val EXTRA_API_KEY = "api_key"
    }
}
