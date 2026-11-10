package com.xblackbytesx.calzone.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.prefsStore by preferencesDataStore("prefs")

/** `dark` (the default, decision 36 in calzone-server), `light` or `system`. */
enum class Theme { DARK, LIGHT, SYSTEM }

data class Prefs(
    val theme: Theme = Theme.DARK,
    /** Collections this phone shows although they are hidden on the web. */
    val shown: Set<String> = emptySet(),
    /** Collections this phone does not show. */
    val hidden: Set<String> = emptySet(),
) {
    /**
     * Whether this phone shows a collection in Android's calendar and
     * contacts (the bridge, steps 5 and 6): the phone's own choice, else
     * what the person chose on the web.
     */
    fun shows(id: String, hiddenOnWeb: Boolean): Boolean = when (id) {
        in shown -> true
        in hidden -> false
        else -> !hiddenOnWeb
    }
}

/** This phone's own settings. Everything the person shares lives on the server. */
class PrefsStore(private val context: Context) {
    private val themeKey = stringPreferencesKey("theme")
    private val shownKey = stringSetPreferencesKey("shown")
    private val hiddenKey = stringSetPreferencesKey("hidden")

    val prefs: Flow<Prefs> = context.prefsStore.data.map { p ->
        Prefs(
            theme = p[themeKey]?.let { runCatching { Theme.valueOf(it) }.getOrNull() } ?: Theme.DARK,
            shown = p[shownKey] ?: emptySet(),
            hidden = p[hiddenKey] ?: emptySet(),
        )
    }

    suspend fun setTheme(t: Theme) {
        context.prefsStore.edit { it[themeKey] = t.name }
    }

    suspend fun setShown(id: String, show: Boolean) {
        context.prefsStore.edit {
            it[shownKey] = (it[shownKey] ?: emptySet()).let { s -> if (show) s + id else s - id }
            it[hiddenKey] = (it[hiddenKey] ?: emptySet()).let { s -> if (show) s - id else s + id }
        }
    }

    suspend fun clearChoices() {
        context.prefsStore.edit {
            it.remove(shownKey)
            it.remove(hiddenKey)
        }
    }
}
