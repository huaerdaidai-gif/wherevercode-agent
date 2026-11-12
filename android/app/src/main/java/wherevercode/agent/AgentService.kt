package wherevercode.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground service: keeps the agent process in the foreground class while
 * the user switches away, so a long agent run is not frozen immediately.
 *
 * Honest limit (see android/README.md): this alone cannot promise "never killed".
 * Battery optimization and vendor (MIUI) background policy still apply; the app
 * asks the user to exempt it, and the probe reports the current state.
 */
class AgentService : Service() {

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID, "Agent 运行中", NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WhereverCode Agent 运行中")
            .setContentText("手机本地的 Agent 正在待命 / 运行")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Keep the service running until explicitly stopped.
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "wherevercode-agent"
        private const val NOTIFICATION_ID = 1
    }
}
