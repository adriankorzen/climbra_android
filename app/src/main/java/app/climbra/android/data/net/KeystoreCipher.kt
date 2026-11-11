package app.climbra.android.data.net

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager

/** AES-256-GCM (Tink) with the keyset wrapped by a key in the Android Keystore. */
class KeystoreCipher(context: Context) : ByteCipher {

    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, PREFERENCES)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    override fun encrypt(plain: ByteArray): ByteArray = aead.encrypt(plain, ASSOCIATED_DATA)

    override fun decrypt(encrypted: ByteArray): ByteArray = aead.decrypt(encrypted, ASSOCIATED_DATA)

    private companion object {
        const val KEYSET_NAME = "cookie_keyset"
        const val PREFERENCES = "climbra_keys"
        const val MASTER_KEY_URI = "android-keystore://climbra_cookie_master_key"
        val ASSOCIATED_DATA = "climbra-cookies".encodeToByteArray()
    }
}
