package dev.pocketharness.agent

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.pocketharness.HarnessApplication
import dev.pocketharness.MainActivity
import dev.pocketharness.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

class AgentService : Service() {
    companion object { const val RUN = "dev.pocketharness.AGENT_RUN"; const val STOP = "dev.pocketharness.AGENT_STOP"; private const val CHANNEL = "agent_work"; private const val NOTIFICATION = 200 }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val coordinator get() = (application as HarnessApplication).agent
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra("show_agent", true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, AgentService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_agent_notification)
            .setContentTitle("Pocket Harness agent").setContentText(text.take(100)).setContentIntent(open)
            .setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(R.drawable.ic_agent_notification, "Stop", stop).build()).build()
    }
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Agent tasks", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { coordinator.stop(); if (!coordinator.state.value.active) stopSelf(); return START_NOT_STICKY }
        val id = intent?.getStringExtra("task_id")
        if (intent?.action != RUN || id == null) { stopSelf(); return START_NOT_STICKY }
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, notification("Starting task"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIFICATION, notification("Starting task"))
        coordinator.launchIn(scope, id) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        scope.launch {
            var last = 0L
            coordinator.state.collect { state ->
                val now = System.currentTimeMillis()
                if (state.active && now - last > 1000) {
                    if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(state.task?.activity ?: "Working"))
                    last = now
                }
            }
        }
        return START_NOT_STICKY
    }
    override fun onTimeout(startId: Int, fgsType: Int) { coordinator.stop(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
    override fun onDestroy() { coordinator.stop(); scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
