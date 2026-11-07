package com.pida.app.data

import android.content.Context
import android.content.SharedPreferences

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var serverBaseUrl: String
        get() = prefs.getString(KEY_SERVER_URL, DEFAULT_URL) ?: DEFAULT_URL
        set(value) {
            val sanitized = value.trim().trimEnd('/')
            prefs.edit().putString(KEY_SERVER_URL, sanitized).apply()
        }

    companion object {
        private const val PREFS_NAME = "pi_local_action_prefs"
        private const val KEY_SERVER_URL = "server_base_url"
        const val DEFAULT_URL = ""

        @Volatile
        private var instance: PreferencesManager? = null

        fun getInstance(context: Context): PreferencesManager {
            return instance ?: synchronized(this) {
                instance ?: PreferencesManager(context.applicationContext).also { instance = it }
            }
        }
    }
}

