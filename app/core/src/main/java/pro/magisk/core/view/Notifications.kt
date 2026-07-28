package pro.magisk.view

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import androidx.core.content.getSystemService
import androidx.core.graphics.drawable.toIcon
import pro.magisk.core.AppContext
import pro.magisk.core.R
import pro.magisk.core.ktx.getBitmap
import pro.magisk.core.ktx.selfLaunchIntent
import java.util.concurrent.atomic.AtomicInteger

@Suppress("DEPRECATION")
object Notifications {

    val mgr by lazy { AppContext.getSystemService<NotificationManager>()!! }

    private const val SU_CHANNEL = "su_notification"

    private val nextId = AtomicInteger(0)

    fun setup() {
        AppContext.apply {
            if (SDK_INT >= Build.VERSION_CODES.O) {
                val channel4 = NotificationChannel(SU_CHANNEL,
                    getString(R.string.su_notification_channel), NotificationManager.IMPORTANCE_HIGH)
                mgr.createNotificationChannels(listOf(channel4))
            }
        }
    }

    private const val SU_NOTIFICATION_TIMEOUT_MS = 3_000L

    @SuppressLint("InlinedApi")
    fun suNotification(granted: Boolean, appName: String) {
        AppContext.apply {
            val flag = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val pending = PendingIntent.getActivity(this, 0, selfLaunchIntent(), flag)
            val title = getString(
                if (granted) R.string.su_notification_granted_title
                else R.string.su_notification_denied_title
            )
            val text = getString(
                if (granted) R.string.su_allow_toast
                else R.string.su_deny_toast,
                appName
            )
            val builder = if (SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, SU_CHANNEL)
                    .setSmallIcon(getBitmap(R.drawable.ic_magisk_outline).toIcon())
            } else {
                Notification.Builder(this).setPriority(Notification.PRIORITY_HIGH)
                    .setSmallIcon(R.drawable.ic_magisk_outline)
            }
                .setContentIntent(pending)
                .setContentTitle(title)
                .setContentText(text)
                .setAutoCancel(true)
                .setTimeoutAfter(SU_NOTIFICATION_TIMEOUT_MS)
            mgr.notify(nextId(), builder.build())
        }
    }

    fun nextId() = nextId.incrementAndGet()
}
