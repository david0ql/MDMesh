package com.dallycontrol.agent.storage

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.storage.StorageManager
import android.widget.Toast
import com.dallycontrol.agent.R

/**
 * Bridge to Android's "clear the cache of all apps?" confirmation: Android only shows it to an app that asks for a
 * result (it checks who is calling), so this invisible screen asks for it and closes when the person answers.
 */
class CacheClearActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        runCatching {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(StorageManager.ACTION_CLEAR_APP_CACHE), REQUEST)
        }.onFailure { finish() }
    }

    @Deprecated("Activity result API is not used by this one-shot bridge")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST && resultCode == RESULT_OK) {
            Toast.makeText(this, R.string.cache_done, Toast.LENGTH_LONG).show()
        }
        finish()
    }

    private companion object { const val REQUEST = 41 }
}
