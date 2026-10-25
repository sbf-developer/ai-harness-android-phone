package dev.pocketharness.agent

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import dev.pocketharness.data.AppJson
import dev.pocketharness.data.UserFacingException
import kotlinx.serialization.json.*
import java.util.Base64

object TermuxContract {
    const val PACKAGE = "com.termux"
    const val PERMISSION = "com.termux.permission.RUN_COMMAND"
    const val SERVICE = "com.termux.app.RunCommandService"
    const val ACTION = "com.termux.RUN_COMMAND"
    const val CALLBACK = "dev.pocketharness.TERMUX_RESULT"
    const val PROBE = "printf 'PH_PROBE_V1\\n'; for t in python git rg ssh; do if command -v \"\$t\" >/dev/null 2>&1; then printf '%s=ok\\n' \"\$t\"; else printf '%s=missing\\n' \"\$t\"; fi; done"
    fun supported(version: String) = Regex("^(\\d+)\\.(\\d+)").find(version)?.let { it.groupValues[1].toInt() > 0 || it.groupValues[2].toInt() >= 109 } ?: false
}
data class TermuxSetup(val installed: Boolean, val permitted: Boolean, val version: String = "", val supported: Boolean = false) {
    val available get() = installed && permitted && supported
    val message get() = when {
        !installed -> "Install the supported com.termux app from F-Droid, open it once, then return here."
        !supported -> "Termux $version is unsupported. Use an up-to-date official F-Droid/GitHub build (RUN_COMMAND callbacks require 0.109+)."
        !permitted -> "Grant Pocket Harness permission to run commands in Termux. Android may list it under Additional permissions."
        else -> "Termux $version is installed and permission is granted. Run the connection check to verify external commands and tools."
    }
}

class TermuxBridge(private val context: Context) {
    @Suppress("DEPRECATION")
    fun setup(): TermuxSetup = try {
        val info = context.packageManager.getPackageInfo(TermuxContract.PACKAGE, 0)
        context.packageManager.getServiceInfo(ComponentName(TermuxContract.PACKAGE, TermuxContract.SERVICE), 0)
        TermuxSetup(true, context.checkSelfPermission(TermuxContract.PERMISSION) == PackageManager.PERMISSION_GRANTED,
            info.versionName.orEmpty(), TermuxContract.supported(info.versionName.orEmpty()))
    } catch (_: PackageManager.NameNotFoundException) { TermuxSetup(false, false) }

    fun commandIntent(id: String, payload: JsonObject, probe: Boolean = false): Intent {
        val callback = Intent(context, TermuxResultReceiver::class.java).setAction(TermuxContract.CALLBACK)
            .setData(Uri.Builder().scheme("pocketharness").authority("termux-result").appendPath(id).build())
        val flags = PendingIntent.FLAG_ONE_SHOT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val pending = PendingIntent.getBroadcast(context, id.hashCode(), callback, flags)
        val arguments = if (probe) arrayOf("--noprofile", "--norc", "-c", TermuxContract.PROBE) else arrayOf(
            "-c", context.assets.open("harness_runner.py").bufferedReader().use { it.readText() },
            Base64.getEncoder().encodeToString(payload.toString().toByteArray(Charsets.UTF_8)))
        return Intent(TermuxContract.ACTION).setComponent(ComponentName(TermuxContract.PACKAGE, TermuxContract.SERVICE))
            .putExtra("com.termux.RUN_COMMAND_PATH", "$TERMUX_BIN/${if (probe) "bash" else "python"}")
            .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arguments)
            .putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME)
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            .putExtra("com.termux.RUN_COMMAND_BACKGROUND_CUSTOM_LOG_LEVEL", 0)
            .putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "Pocket Harness")
            .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pending)
    }
    fun send(id: String, payload: JsonObject, probe: Boolean = false) {
        val status = setup()
        if (!status.available) throw UserFacingException(status.message)
        try { context.startService(commandIntent(id, payload, probe)) ?: throw UserFacingException("Termux command service did not start. Open Termux and run setup first.") }
        catch (_: SecurityException) { throw UserFacingException("Android denied Termux access. Grant RUN_COMMAND permission to Pocket Harness and enable allow-external-apps in Termux.") }
        catch (_: IllegalStateException) { throw UserFacingException("Android blocked the Termux service. Keep Pocket Harness visible, open Termux once, and try again.") }
    }
}

object TermuxResultDecoder {
    fun decode(id: String, stdout: String, stderr: String, exit: Int, error: Int, message: String, probe: Boolean = false): ToolResult {
        if (error != -1) return ToolResult(stderr = message.take(8000), exitCode = exit,
            error = "Termux could not execute the request. Check permission, allow-external-apps=true, and installed Python/tools. ${message.take(1000)}")
        if (probe) {
            if (exit != 0 || !stdout.startsWith("PH_PROBE_V1")) return ToolResult(error = "Termux probe failed. Open Termux and run the setup commands.", stderr = stderr.take(4000), exitCode = exit)
            return ToolResult(stdout = stdout.take(4000), exitCode = exit,
                data = buildJsonObject { put("ready", listOf("python", "git", "rg", "ssh").all { stdout.contains("$it=ok") }) })
        }
        val obj = try { AppJson.parseToJsonElement(stdout.trim()).jsonObject }
        catch (_: Exception) { return ToolResult(exitCode = exit, stderr = stderr.take(4000), error = "No complete runner result was received. Python may be missing, output transport may have failed, or Android stopped Termux. Inspect the command outcome before retrying.") }
        if (obj.text("command_id") != id) return ToolResult(error = "The command result ID did not match. No outcome was accepted.")
        val extra = (obj["data"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        extra["command_id"] = JsonPrimitive(id)
        extra["confirmed"] = JsonPrimitive(true)
        obj["cancelled"]?.let { extra["cancelled"] = it }
        return ToolResult(stdout = obj.text("stdout").take(MAX_TOOL_TEXT), stderr = obj.text("stderr").take(8000),
            exitCode = obj["exit_code"]?.jsonPrimitive?.intOrNull ?: exit, cwd = obj.text("cwd"),
            timedOut = obj["timed_out"]?.jsonPrimitive?.booleanOrNull ?: false,
            truncated = obj["truncated"]?.jsonPrimitive?.booleanOrNull ?: false,
            data = JsonObject(extra), error = obj["error"]?.jsonPrimitive?.contentOrNull)
    }
}
