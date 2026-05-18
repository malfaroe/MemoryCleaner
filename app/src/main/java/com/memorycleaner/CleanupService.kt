package com.memorycleaner

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.IBinder
import android.os.StatFs
import android.os.storage.StorageManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CleanupService : Service() {

    companion object {
        const val CHANNEL_PROGRESS = "cleanup_progress"
        const val CHANNEL_RESULT   = "cleanup_result"
        const val NOTIF_PROGRESS   = 1
        const val NOTIF_RESULT     = 2

        // Archivos en Descargas con mas de 30 dias y mas de 10 MB se consideran candidatos
        const val OLD_FILE_DAYS    = 30L
        const val MIN_FILE_SIZE_MB = 10L
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_PROGRESS, buildProgressNotification())
        Thread { runCleanup() }.start()
        return START_NOT_STICKY
    }

    private fun runCleanup() {
        val storageBefore = getFreeStorageMb()

        var freedBySystem  = 0L
        var freedByOwn     = 0L
        var freedByStale   = 0L

        // 1. Pedir al sistema que limpie el cache acumulado de todas las apps
        freedBySystem = requestSystemCacheCleanup()

        // 2. Limpiar cache propio de esta app
        freedByOwn = cleanOwnCache()

        // 3. Eliminar archivos viejos y grandes de la carpeta Descargas
        freedByStale = cleanStaleDownloads()

        // Calcular espacio real liberado comparando antes/despues
        Thread.sleep(500)
        val storageAfter = getFreeStorageMb()
        val totalFreed   = (storageAfter - storageBefore).coerceAtLeast(0)

        // Guardar resultado
        val timestamp = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
        getSharedPreferences("prefs", Context.MODE_PRIVATE).edit()
            .putString("last_run", timestamp)
            .putLong("storage_before_mb", storageBefore)
            .putLong("storage_after_mb", storageAfter)
            .putLong("freed_total_mb", totalFreed)
            .putLong("freed_system_mb", freedBySystem)
            .putLong("freed_own_mb", freedByOwn)
            .putLong("freed_stale_mb", freedByStale)
            .apply()

        showResultNotification(totalFreed, freedBySystem, freedByStale, timestamp)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Usa StorageManager.allocateBytes() para pedirle a Android que libere
     * el cache de todas las apps hasta el maximo posible.
     * Devuelve MB que se intentaron liberar (estimado).
     */
    private fun requestSystemCacheCleanup(): Long {
        return try {
            val storageManager = getSystemService(StorageManager::class.java)
            val uuid = storageManager.getUuidForPath(filesDir)

            // Bytes que Android puede liberar limpiando caches de otras apps
            val allocatable = storageManager.getAllocatableBytes(uuid)
            if (allocatable > 0) {
                storageManager.allocateBytes(uuid, allocatable)
            }
            allocatable / (1024 * 1024)
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Limpia el cache de esta propia app en almacenamiento interno y externo.
     */
    private fun cleanOwnCache(): Long {
        var freed = 0L
        listOf(cacheDir, externalCacheDir, codeCacheDir).forEach { dir ->
            freed += dir?.deleteContentsAndMeasure() ?: 0L
        }
        return freed / (1024 * 1024)
    }

    /**
     * Elimina archivos de Descargas que tengan mas de OLD_FILE_DAYS dias
     * y ocupen mas de MIN_FILE_SIZE_MB MB.
     */
    private fun cleanStaleDownloads(): Long {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloads.exists()) return 0L

        val cutoff  = System.currentTimeMillis() - OLD_FILE_DAYS * 24 * 60 * 60 * 1000L
        val minSize = MIN_FILE_SIZE_MB * 1024 * 1024
        var freed   = 0L

        downloads.listFiles()?.forEach { file ->
            if (file.isFile &&
                file.length() >= minSize &&
                file.lastModified() < cutoff) {
                val size = file.length()
                if (file.delete()) {
                    freed += size
                }
            }
        }
        return freed / (1024 * 1024)
    }

    private fun getFreeStorageMb(): Long {
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        return stat.availableBytes / (1024 * 1024)
    }

    // Extension: borra contenido de un directorio y devuelve bytes liberados
    private fun File.deleteContentsAndMeasure(): Long {
        var freed = 0L
        listFiles()?.forEach { child ->
            freed += child.length()
            child.deleteRecursively()
        }
        return freed
    }

    private fun buildProgressNotification() =
        NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setContentTitle("Liberando almacenamiento...")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .build()

    private fun showResultNotification(
        totalMb: Long, systemMb: Long, staleMb: Long, timestamp: String
    ) {
        val mainIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val summary = if (totalMb > 0) "${totalMb} MB liberados" else "Almacenamiento ya optimizado"
        val detail  = buildString {
            if (systemMb > 0) appendLine("- Cache del sistema: ${systemMb} MB")
            if (staleMb  > 0) appendLine("- Archivos viejos en Descargas: ${staleMb} MB")
            appendLine(timestamp)
        }

        val notif = NotificationCompat.Builder(this, CHANNEL_RESULT)
            .setContentTitle("Limpieza completada")
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$summary\n$detail"))
            .setSmallIcon(android.R.drawable.ic_delete)
            .setContentIntent(mainIntent)
            .setAutoCancel(true)
            .build()

        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIF_RESULT, notif)
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Limpieza en progreso",
                NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, "Resultado de limpieza",
                NotificationManager.IMPORTANCE_DEFAULT)
        )
    }
}
