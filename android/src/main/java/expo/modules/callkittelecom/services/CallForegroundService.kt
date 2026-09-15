package expo.modules.callkittelecom.services

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import expo.modules.callkittelecom.utils.CallKitTelecomLog

/**
 * Keeps a live call's process AND network alive while the app is backgrounded.
 *
 * Core-Telecom's `CallsManager.addCall` grants only a `phoneCall`-type foreground
 * service delegation, which keeps the process schedulable but does NOT exempt the
 * app's own sockets (e.g. a WebRTC signalling WebSocket) from background/Doze
 * network reaping. Without a network-bearing foreground service, a backgrounded
 * call loses its socket within a few seconds and drops.
 *
 * This service promotes the module's existing ongoing-call notification
 * (NOTIFICATION_ID = 8400) to a real foreground service that also carries
 * `dataSync` — the type that keeps the socket alive in the background. Reusing the
 * same notification id means Android renders ONE notification, not two.
 *
 * `dataSync` (not `microphone`): Android disallows starting the `microphone` FGS
 * type from the background, and Telecom already owns call audio; `dataSync` is the
 * lower-friction network-bearing type. Note `dataSync` has a ~6h/day cumulative
 * cap on Android 15 — fine per call because the service stops at each call end.
 */
class CallForegroundService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = pendingNotification
        if (notification == null) {
            // Nothing to show — never leave a bare foreground service running.
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, foregroundTypes())
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            CallKitTelecomLog.e(TAG) { "startForeground failed: ${e.message}" }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        pendingNotification = null
        super.onDestroy()
    }

    private fun foregroundTypes(): Int {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        return types
    }

    companion object {
        private const val TAG = "CallForegroundService"

        // Must match CallNotificationManager.NOTIFICATION_ID so the FGS adopts the
        // existing ongoing-call notification (one notification, not two).
        private const val NOTIFICATION_ID = 8400

        // startForegroundService only carries an Intent, so the built Notification
        // is handed over here rather than parcelled.
        @Volatile
        private var pendingNotification: Notification? = null

        /**
         * Promote the ongoing-call notification to a foreground service. Idempotent:
         * calling it again while running updates the held notification and re-issues
         * startForegroundService, which the OS treats as a notification update.
         */
        fun start(context: Context, notification: Notification) {
            pendingNotification = notification
            val ctx = context.applicationContext
            val intent = Intent(ctx, CallForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            } catch (e: Exception) {
                // A background-start restriction (no active call context yet) — the
                // notification itself is still posted by CallNotificationManager, so
                // the call is not invisible; it just lacks the network exemption.
                CallKitTelecomLog.e(TAG) { "startForegroundService failed: ${e.message}" }
                pendingNotification = null
            }
        }

        /** Stop the foreground service; the notification's own lifecycle is unchanged. */
        fun stop(context: Context) {
            pendingNotification = null
            val ctx = context.applicationContext
            try {
                ctx.stopService(Intent(ctx, CallForegroundService::class.java))
            } catch (e: Exception) {
                CallKitTelecomLog.e(TAG) { "stopService failed: ${e.message}" }
            }
        }
    }
}
