package dev.otpbridge.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.otpbridge.MainActivity
import dev.otpbridge.R
import dev.otpbridge.Telemetry

/**
 * Receives content-free wake-ups. It never contacts the relay, unlocks the vault or
 * reads requests in the background: it only tells the user to open the app, where the
 * normal foreground fetch, review and biometric approval happen.
 */
class PushService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        Telemetry.init(this)
        if (message.data["t"] != "wake") return
        Telemetry.track("push.received", "priority" to message.priority)
        Notifications.showRequestWaiting(this)
    }
    override fun onNewToken(token: String) {
        Telemetry.init(this)
        // Registered with relays on the next app open (network only runs in the foreground).
        getSharedPreferences("push", MODE_PRIVATE).edit().putString("token", token).putBoolean("dirty", true).apply()
        Telemetry.track("push.token-rotated")
    }
}

object Notifications {
    const val CHANNEL = "otp_requests"
    private const val ID = 1001
    const val EXTRA_FROM_PUSH = "fromPush"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "OTP requests", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Alerts when a browser asks for a code. Never shows the code itself."
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        })
    }

    fun permissionGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun enabled(context: Context): Boolean {
        if (!permissionGranted(context) || !NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT < 26) return true
        return context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun showRequestWaiting(context: Context) {
        ensureChannel(context)
        if (!enabled(context)) { Telemetry.track("push.notification-blocked"); return }
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra(EXTRA_FROM_PUSH, true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("OTP request waiting")
            .setContentText("A browser is asking for a code. Tap to review.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setTimeoutAfter(90_000) // Requests expire after 90 s.
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try { NotificationManagerCompat.from(context).notify(ID, n) } catch (_: SecurityException) { Telemetry.track("push.notification-blocked") }
    }

    fun clear(context: Context) = NotificationManagerCompat.from(context).cancel(ID)
}
