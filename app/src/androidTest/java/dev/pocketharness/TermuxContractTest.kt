package dev.pocketharness

import android.app.PendingIntent
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import dev.pocketharness.agent.*
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.*
import org.junit.Test

class TermuxContractTest {
    @Suppress("DEPRECATION")
    @Test fun explicitOneShotCallbackAndOfficialRunCommandService() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bridge = TermuxBridge(context)
        val intent = bridge.commandIntent("00000000-0000-0000-0000-000000000001", buildJsonObject {})
        assertEquals("com.termux.RUN_COMMAND", intent.action)
        assertEquals("com.termux", intent.component?.packageName)
        assertEquals("com.termux.app.RunCommandService", intent.component?.className)
        assertEquals("$TERMUX_BIN/python", intent.getStringExtra("com.termux.RUN_COMMAND_PATH"))
        assertTrue(intent.getBooleanExtra("com.termux.RUN_COMMAND_BACKGROUND", false))
        val pending = intent.getParcelableExtra<PendingIntent>("com.termux.RUN_COMMAND_PENDING_INTENT")!!
        assertEquals(context.packageName, pending.creatorPackage)
        if (Build.VERSION.SDK_INT >= 31) assertFalse(pending.isImmutable)
        pending.cancel()
    }
}
