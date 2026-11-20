package app.climbra.android.data.net

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager

/**
 * AES-256-GCM (Tink) with the keyset wrapped by a key in the Android Keystore. [key] names the keyset, its Keystore
 * key and the associated data: the cookies and the offline answers (spec 018) have a key each.
 */
class KeystoreCipher(context: Context, private val key: Key = Key.COOKIES) : ByteCipher {

    enum class Key(val keyset: String, val masterKeyUri: String, associatedData: String) {
        COOKIES("cookie_keyset", "android-keystore://climbra_cookie_master_key", "climbra-cookies"),
        OFFLINE("offline_keyset", "android-keystore://climbra_offline_master_key", "climbra-offline"),
        ;

        val associatedData = associatedData.encodeToByteArray()
    }

    private val aead: Aead by lazy {
        AeadConfig.register()
        AndroidKeysetManager.Builder()
            .withSharedPref(context, key.keyset, PREFERENCES)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(key.masterKeyUri)
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    override fun encrypt(plain: ByteArray): ByteArray = aead.encrypt(plain, key.associatedData)

    override fun decrypt(encrypted: ByteArray): ByteArray = aead.decrypt(encrypted, key.associatedData)

    private companion object {
        const val PREFERENCES = "climbra_keys"
    }
}
