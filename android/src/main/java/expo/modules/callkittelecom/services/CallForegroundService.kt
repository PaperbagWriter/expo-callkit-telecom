package expo.modules.callkittelecom.services

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

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
        // A service started via startForegroundService MUST call startForeground()
        // within a few seconds or the OS raises a timeout and kills it (which, on a
        // call, drops the call). So ALWAYS call startForeground — never take an
        // early-return path that skips it. The notification rides in the Intent (a
        // Notification is Parcelable), which is reliable across the
        // startForegroundService -> onStartCommand boundary, unlike a static field.
        val notification: Notification? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra(EXTRA_NOTIFICATION, Notification::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(EXTRA_NOTIFICATION)
            }

        if (notification == null) {
            // Should not happen (start() always packs it), but we still cannot
            // leave a foreground-started service without a startForeground call.
            Log.w(TAG, "onStartCommand with no notification — stopping")
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
            // Log at ERROR unconditionally: a failure here is the difference between
            // a call surviving the background and dropping, and it's otherwise silent.
            Log.e(TAG, "startForeground failed", e)
            stopSelf()
        }
        return START_NOT_STICKY
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
        private const val EXTRA_NOTIFICATION = "notification"

        // Must match CallNotificationManager.NOTIFICATION_ID so the FGS adopts the
        // existing ongoing-call notification (one notification, not two).
        private const val NOTIFICATION_ID = 8400

        /**
         * Promote the ongoing-call notification to a foreground service. Idempotent:
         * calling it again while running re-delivers with the current notification,
         * which the OS treats as a notification update.
         *
         * Start this as EARLY as the call is live and the app is still foreground —
         * a foreground start has no background-start restrictions, and once running
         * the FGS survives the subsequent backgrounding.
         */
        fun start(context: Context, notification: Notification) {
            val ctx = context.applicationContext
            val intent =
                Intent(ctx, CallForegroundService::class.java)
                    .putExtra(EXTRA_NOTIFICATION, notification)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ctx.startForegroundService(intent)
                } else {
                    ctx.startService(intent)
                }
            } catch (e: Exception) {
                // A background-start restriction (e.g. started too late, already in
                // the background) — the notification itself is still posted by
                // CallNotificationManager, so the call is not invisible; it just
                // lacks the network exemption.
                Log.e(TAG, "startForegroundService failed", e)
            }
        }

        /** Stop the foreground service; the notification's own lifecycle is unchanged. */
        fun stop(context: Context) {
            val ctx = context.applicationContext
            try {
                ctx.stopService(Intent(ctx, CallForegroundService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "stopService failed", e)
            }
        }
    }
}
