package com.mrit.mesh.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mrit.mesh.mesh.MeshNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * MeshService — foreground service that keeps MeshNode alive in the background.
 *
 * Phase 2: All routing logic has moved into MeshNode.
 * This service is now thin — it just owns the MeshNode lifecycle and the
 * foreground notification so Android doesn't kill us.
 *
 * The node is a singleton accessible via [MeshService.node] from anywhere in the app.
 */
class MeshService : Service() {

    companion object {
        private const val TAG             = "MeshService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID      = "mrit_mesh_channel"

        /**
         * The live MeshNode — non-null while the service is running.
         * MainActivity and other components access the node through here.
         */
        @Volatile
        var node: MeshNode? = null
            private set

        fun startIntent(context: Context) = Intent(context, MeshService::class.java)
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        node = MeshNode(applicationContext).also { it.start() }
        Log.d(TAG, "MeshService created — node ID: ${node!!.ourId.shortId()}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        Log.d(TAG, "MeshService running")
        return START_STICKY
    }

    override fun onDestroy() {
        node?.stop()
        node = null
        scope.cancel()
        super.onDestroy()
        Log.d(TAG, "MeshService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Foreground notification ────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "MRIT Mesh Network",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the MRIT mesh node running in the background"
            }
            manager.createNotificationChannel(channel)
        }

        val shortId = node?.ourId?.shortId() ?: "------"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("MRIT Mesh Active")
            .setContentText("Node $shortId — scanning for peers")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }
}
