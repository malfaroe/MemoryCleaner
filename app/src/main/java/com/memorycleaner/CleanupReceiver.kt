package com.memorycleaner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class CleanupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Lanzar servicio en primer plano para hacer la limpieza
        val serviceIntent = Intent(context, CleanupService::class.java)
        ContextCompat.startForegroundService(context, serviceIntent)

        // Reprogramar para la siguiente medianoche
        CleanupScheduler.schedule(context)
    }
}
