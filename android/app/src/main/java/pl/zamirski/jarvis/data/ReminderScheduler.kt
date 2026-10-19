package pl.zamirski.jarvis.data

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import pl.zamirski.jarvis.R
import pl.zamirski.jarvis.core.Reminder
import pl.zamirski.jarvis.ui.MainActivity

/**
 * Przypomnienia przez AlarmManager. Używamy alarmów niedokładnych (bez specjalnego uprawnienia),
 * więc powiadomienie może przyjść z kilkuminutowym opóźnieniem — ekran Przypomnień mówi to wprost.
 */
object ReminderScheduler {
    const val CHANNEL_ID = "jarvis_reminders"
    private const val EXTRA_ID = "reminder_id"

    fun schedule(context: Context, reminder: Reminder) {
        if (reminder.atMillis <= System.currentTimeMillis()) return
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminder.atMillis, pending(context, reminder.id))
    }

    fun cancel(context: Context, id: Long) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(context, id))
    }

    fun rescheduleAll(context: Context) {
        LocalStore.get(context).reminders.value.forEach { schedule(context, it) }
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun pending(context: Context, id: Long): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            context,
            (id % Int.MAX_VALUE).toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Przypomnienia Jarvisa", NotificationManager.IMPORTANCE_HIGH),
        )
    }

    internal fun idFrom(intent: Intent): Long = intent.getLongExtra(EXTRA_ID, -1)
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = ReminderScheduler.idFrom(intent)
        val store = LocalStore.get(context)
        val reminder = store.reminder(id) ?: return
        ReminderScheduler.ensureChannel(context)
        if (ReminderScheduler.canNotify(context)) {
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Jarvis — przypomnienie")
                .setContentText(reminder.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            try {
                NotificationManagerCompat.from(context).notify((id % Int.MAX_VALUE).toInt(), notification)
            } catch (e: SecurityException) {
                // Brak zgody na powiadomienia — nic więcej nie możemy zrobić.
            }
        }
        store.deleteReminder(id)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) ReminderScheduler.rescheduleAll(context)
    }
}
