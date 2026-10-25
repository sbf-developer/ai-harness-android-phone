package dev.pocketharness.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.pocketharness.HarnessApplication
import kotlinx.coroutines.*

/** Non-exported; Termux receives only the explicit one-shot PendingIntent capability. */
class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TermuxContract.CALLBACK || intent.data?.scheme != "pocketharness" || intent.data?.authority != "termux-result") return
        val id = intent.data?.lastPathSegment ?: return
        if (!id.matches(Regex("[a-f0-9-]{36}"))) return
        val result = try { intent.getBundleExtra("result") } catch (_: Exception) { null } ?: return
        val stdout = result.getString("stdout").orEmpty().take(100000)
        val stderr = result.getString("stderr").orEmpty().take(16000)
        val error = result.getInt("err", -1)
        val exit = result.getInt("exitCode", -1)
        val message = result.getString("errmsg").orEmpty().take(4000)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try { (context.applicationContext as HarnessApplication).agent.acceptResult(id, stdout, stderr, exit, error, message) }
            catch (_: Exception) { /* No result body, command or credential is ever logged. No replay. */ }
            finally { pending.finish() }
        }
    }
}
