package dev.killua.iptv.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.killua.iptv.domain.model.AppFailure
import dev.killua.iptv.domain.model.AppFailureException
import dev.killua.iptv.domain.model.FailureKind
import dev.killua.iptv.domain.model.XtreamCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface CredentialVault {
    suspend fun load(): XtreamCredentials?
    suspend fun save(credentials: XtreamCredentials)
    suspend fun clear()
}

class AndroidCredentialVault(
    private val dataStore: DataStore<Preferences>,
) : CredentialVault {
    private object Keys {
        val version = intPreferencesKey("secure.credentials.version")
        val accountId = stringPreferencesKey("secure.credentials.account_id")
        val iv = stringPreferencesKey("secure.credentials.iv")
        val ciphertext = stringPreferencesKey("secure.credentials.ciphertext")
    }

    override suspend fun load(): XtreamCredentials? {
        val preferences = dataStore.data.first()
        val accountId = preferences[Keys.accountId] ?: return null
        val iv = preferences[Keys.iv]
        val ciphertext = preferences[Keys.ciphertext]
        val version = preferences[Keys.version]
        if (version != RECORD_VERSION || iv == null || ciphertext == null) {
            clear()
            throw secureStorageFailure()
        }

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(GCM_TAG_BITS, Base64.decode(iv, Base64.NO_WRAP)),
            )
            cipher.updateAAD(accountId.toByteArray(Charsets.UTF_8))
            val cleartext = cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP))
            CredentialCodec.decode(cleartext).also {
                require(it.accountId == accountId) { "Credential account mismatch" }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            clear()
            throw secureStorageFailure()
        }
    }

    override suspend fun save(credentials: XtreamCredentials) {
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            cipher.updateAAD(credentials.accountId.toByteArray(Charsets.UTF_8))
            val encrypted = cipher.doFinal(CredentialCodec.encode(credentials))
            dataStore.edit { preferences ->
                preferences[Keys.version] = RECORD_VERSION
                preferences[Keys.accountId] = credentials.accountId
                preferences[Keys.iv] = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
                preferences[Keys.ciphertext] = Base64.encodeToString(encrypted, Base64.NO_WRAP)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            throw secureStorageFailure()
        }
    }

    override suspend fun clear() {
        dataStore.edit { preferences ->
            preferences.remove(Keys.version)
            preferences.remove(Keys.accountId)
            preferences.remove(Keys.iv)
            preferences.remove(Keys.ciphertext)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            generateKey()
        }
    }

    private fun secureStorageFailure() = AppFailureException(
        AppFailure(FailureKind.SecureStorageUnavailable),
    )

    private companion object {
        const val RECORD_VERSION = 1
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "killuas_iptv_credentials_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
