package dev.pocketharness

import android.app.Application
import dev.pocketharness.agent.AgentCoordinator
import dev.pocketharness.data.Vault

class HarnessApplication : Application() {
    val vault by lazy { Vault(this) }
    val agent by lazy { AgentCoordinator(this, vault) }
}
