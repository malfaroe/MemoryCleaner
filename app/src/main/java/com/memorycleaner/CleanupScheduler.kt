package com.memorycleaner

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

object CleanupScheduler {

    // (hora, minuto, requestCode)
    private val SCHEDULES = listOf(
        Triple(0,  0,  1001),  // 00:00 medianoche
        Triple(6, 30, 1002)    // 06:30 manana
    )

    fun schedule(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        var nextRun = Long.MAX_VALUE

        for ((hour, minute, rc) in SCHEDULES) {
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }
            val pi = PendingIntent.getBroadcast(
                context, rc, Intent(context, CleanupReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            if (cal.timeInMillis < nextRun) nextRun = cal.timeInMillis
        }

        context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            .edit().putLong("next_run", nextRun).apply()
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        for ((_, _, rc) in SCHEDULES) {
            val pi = PendingIntent.getBroadcast(
                context, rc, Intent(context, CleanupReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(pi)
        }
    }
}
