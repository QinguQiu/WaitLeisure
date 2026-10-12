package com.selflock.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.selflock.R
import com.selflock.lock.LockScreenActivity

object Notifications {

    const val CHANNEL_LOCKING = "locking"
    const val ID_LOCKING = 1001

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_LOCKING,
            context.getString(R.string.notif_channel_locking),
            // IMPORTANCE_LOW：锁机通知只需要「可见、常驻」，不需要响铃打扰。
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notif_channel_locking_desc)
            setShowBadge(false)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    /** 锁机期间的常驻通知。点按回到锁机页。 */
    fun buildLockingNotification(context: Context): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, LockScreenActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(context, CHANNEL_LOCKING)
            .setSmallIcon(R.drawable.ic_stat_lock)
            .setContentTitle(context.getString(R.string.notif_locking_title))
            .setContentText(context.getString(R.string.notif_locking_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }
}
