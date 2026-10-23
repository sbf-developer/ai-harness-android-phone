package dev.pocketharness.data

/** Storage boundary used to exercise failure and cancellation without device credentials. */
interface LocalStore {
    suspend fun loadSettings(): Settings
    suspend fun saveSettings(settings: Settings)
    suspend fun loadConversations(): List<Conversation>
    suspend fun saveConversations(items: List<Conversation>)
}
