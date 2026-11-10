package com.xblackbytesx.calzone.account

import android.accounts.AccountManager
import android.content.ContentResolver
import android.content.Context
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xblackbytesx.calzone.R
import com.xblackbytesx.calzone.net.OkHttpTransport
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import android.accounts.Account as AndroidAccount

private val Context.sessionStore by preferencesDataStore("session")

/** Signed in to one server with this phone's device token. */
data class Signed(val server: String, val token: String) {
    val transport: OkHttpTransport get() = OkHttpTransport(server, token)
}

/** Encrypts the device token at rest. */
interface TokenCipher {
    fun seal(plain: String): String
    fun open(sealed: String): String?
}

/**
 * AES-GCM under a key that lives in the Android Keystore (hardware-backed
 * where the phone has it) and never leaves it: a copy of the app's files
 * carries no usable token (the wordwarz way).
 */
class KeystoreCipher(private val alias: String = "calzone-token") : TokenCipher {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    override fun seal(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return Base64.encodeToString(c.iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP)
    }

    override fun open(sealed: String): String? = runCatching {
        val all = Base64.decode(sealed, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, all, 0, 12))
        String(c.doFinal(all, 12, all.size - 12))
    }.getOrNull()
}

/**
 * The server and device token (one server per phone). The engine's store
 * holds everything else; signing out wipes both.
 */
class Session(private val context: Context, private val cipher: TokenCipher) {
    private val serverKey = stringPreferencesKey("server")
    private val tokenKey = stringPreferencesKey("token")

    /** Whether a session is stored (the token itself stays sealed). */
    val signedIn = context.sessionStore.data.map { it[serverKey] != null && it[tokenKey] != null }

    suspend fun current(): Signed? {
        val p = context.sessionStore.data.first()
        val server = p[serverKey] ?: return null
        val token = p[tokenKey]?.let(cipher::open) ?: return null
        return Signed(server, token)
    }

    suspend fun save(s: Signed) {
        context.sessionStore.edit {
            it[serverKey] = s.server
            it[tokenKey] = cipher.seal(s.token)
        }
    }

    suspend fun forget() {
        context.sessionStore.edit { it.clear() }
    }
}

/**
 * The Android account the sync adapters hang on (decision 14 in
 * calzone-server): one per phone, named after the person and the server.
 * Removing it removes whatever the bridge put in Android's calendar and
 * contacts (steps 5 and 6).
 */
object AndroidAccounts {
    val AUTHORITIES = listOf(CalendarContract.AUTHORITY, ContactsContract.AUTHORITY)
    private const val DAY_SECONDS = 24L * 60 * 60

    fun type(context: Context): String = context.getString(R.string.account_type)

    fun current(context: Context): AndroidAccount? =
        AccountManager.get(context).getAccountsByType(type(context)).firstOrNull()

    /** `anna@calzone.example.com`, the way Fossify and Contacts list it. */
    fun nameFor(user: String, server: String): String = "$user@" + server.substringAfter("://").substringBefore('/')

    /**
     * Make sure exactly this account exists and syncs: Android starts it
     * after edits in other apps (upload), on their refresh, and daily.
     */
    fun ensure(context: Context, name: String): AndroidAccount {
        val am = AccountManager.get(context)
        val type = type(context)
        am.getAccountsByType(type).filter { it.name != name }.forEach { am.removeAccountExplicitly(it) }
        val account = AndroidAccount(name, type)
        am.addAccountExplicitly(account, null, null)
        for (authority in AUTHORITIES) {
            ContentResolver.setIsSyncable(account, authority, 1)
            ContentResolver.setSyncAutomatically(account, authority, true)
            ContentResolver.addPeriodicSync(account, authority, Bundle.EMPTY, DAY_SECONDS)
        }
        return account
    }

    fun remove(context: Context) {
        val am = AccountManager.get(context)
        am.getAccountsByType(type(context)).forEach { am.removeAccountExplicitly(it) }
    }
}
