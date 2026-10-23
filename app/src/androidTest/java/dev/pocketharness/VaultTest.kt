package dev.pocketharness

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pocketharness.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VaultTest {
    @Test fun keystoreRoundTripEncryptedAtRestAndTamperDetection() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = Vault(context)
        val original = vault.loadSettings()
        val file = File(context.noBackupFilesDir, "settings.enc")
        val secret = "test-only-secret-not-a-real-key"
        try {
            val configured = Settings(apiKey = secret, githubToken = "test-only-token")
            vault.saveSettings(configured)
            assertEquals(secret, Vault(context).loadSettings().apiKey)
            val ciphertext = file.readBytes()
            assertFalse(ciphertext.toString(Charsets.ISO_8859_1).contains(secret))
            vault.saveSettings(configured)
            assertFalse("A fresh GCM IV is required", ciphertext.copyOfRange(1, 13).contentEquals(file.readBytes().copyOfRange(1, 13)))
            val tampered = file.readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            file.writeBytes(tampered)
            try { vault.loadSettings(); fail("Tampering was accepted") } catch (_: javax.crypto.AEADBadTagException) { }
        } finally { vault.saveSettings(original) }
    }
    @Test fun conversationsAndAttachedContextSurviveReload() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = Vault(context)
        val originals = vault.loadConversations()
        try {
            val snapshot = RepoSnapshot(Repo("test", "local", "main"), "main", "base", "tree")
            val file = ContextFile(snapshot, "test.kt", "blob", "100644", "sample text\n")
            val conversation = Conversation(title = "Local test", messages = listOf(Message(role = "user", content = "Persist this", files = listOf(file))))
            vault.saveConversations(listOf(conversation))
            assertEquals(listOf(conversation), Vault(context).loadConversations())
            assertFalse(File(context.noBackupFilesDir, "conversations.enc").readBytes().toString(Charsets.ISO_8859_1).contains("Persist this"))
        } finally { vault.saveConversations(originals) }
    }
}
