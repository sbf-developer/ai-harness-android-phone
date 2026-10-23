package dev.pocketharness.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Versioned AES-GCM envelope. Key material never leaves Android Keystore; files never enter backup. */
class Vault(context: Context) {
    private val root = context.noBackupFilesDir
    private val mutex = Mutex()
    private val alias = "pocket-harness-v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    private fun read(name: String): String? {
        val file = AtomicFile(File(root, name))
        if (!file.baseFile.exists() && !File(root, "$name.bak").exists()) return null
        val bytes = file.openRead().use { it.readBytes() }
        require(bytes.size >= 30 && bytes[0] == 1.toByte()) { "Invalid encrypted storage" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(bytes.copyOfRange(13, bytes.size)).toString(Charsets.UTF_8)
    }

    private fun write(name: String, text: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(name.toByteArray(Charsets.UTF_8))
        val bytes = byteArrayOf(1) + cipher.iv + cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        val file = AtomicFile(File(root, name))
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }

    suspend fun loadSettings(): Settings = withContext(Dispatchers.IO) { mutex.withLock {
        read("settings.enc")?.let { AppJson.decodeFromString<Settings>(it) } ?: Settings()
    } }
    suspend fun saveSettings(settings: Settings) = withContext(Dispatchers.IO) { mutex.withLock {
        write("settings.enc", AppJson.encodeToString(settings))
    } }
    suspend fun loadConversations(): List<Conversation> = withContext(Dispatchers.IO) { mutex.withLock {
        read("conversations.enc")?.let { AppJson.decodeFromString<List<Conversation>>(it) } ?: emptyList()
    } }
    suspend fun saveConversations(items: List<Conversation>) = withContext(Dispatchers.IO) { mutex.withLock {
        write("conversations.enc", AppJson.encodeToString(items))
    } }
    suspend fun clearCredentials() = saveSettings(loadSettings().copy(apiKey = "", githubToken = ""))
}
