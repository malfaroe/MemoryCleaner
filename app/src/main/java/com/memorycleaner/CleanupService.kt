package com.memorycleaner

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
        const val OLD_FILE_DAYS    = 14L
        const val MIN_FILE_SIZE_MB = 1L
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
        val storageBefore   = getFreeStorageMb()
        val freedBySystem   = requestSystemCacheCleanup()
        val freedByOwn      = cleanOwnCache()
        val freedByExtCache = cleanExternalAppCaches()
        val freedByThumbs   = cleanThumbnails()
        val freedByStale    = cleanStaleDownloads()
        val killedApps      = killBackgroundApps()

        Thread.sleep(1000)
        val storageAfter = getFreeStorageMb()
        val measured     = freedByExtCache + freedByThumbs + freedByStale + freedByOwn
        val totalFreed   = maxOf(storageAfter - storageBefore, measured).coerceAtLeast(0)

        val timestamp = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
        getSharedPreferences("prefs", Context.MODE_PRIVATE).edit()
            .putString("last_run", timestamp)
            .putLong("storage_before_mb", storageBefore)
            .putLong("storage_after_mb", storageAfter)
            .putLong("freed_total_mb", totalFreed)
            .putLong("freed_system_mb", freedBySystem)
            .putLong("freed_own_mb", freedByOwn)
            .putLong("freed_ext_cache_mb", freedByExtCache)
            .putLong("freed_thumbs_mb", freedByThumbs)
            .putLong("freed_stale_mb", freedByStale)
            .putInt("killed_apps", killedApps)
            .apply()

        showResultNotification(totalFreed, freedBySystem, freedByExtCache, freedByThumbs, freedByStale, killedApps, timestamp)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun requestSystemCacheCleanup(): Long {
        return try {
            val storageManager = getSystemService(StorageManager::class.java)
            val uuid = storageManager.getUuidForPath(filesDir)
            val allocatable = storageManager.getAllocatableBytes(uuid)
            if (allocatable > 0) storageManager.allocateBytes(uuid, allocatable)
            allocatable / (1024 * 1024)
        } catch (_: Exception) { 0L }
    }

    private fun cleanOwnCache(): Long {
        var freed = 0L
        listOf(cacheDir, externalCacheDir, codeCacheDir).forEach { dir ->
            freed += dir?.deleteContentsAndMeasure() ?: 0L
        }
        return freed / (1024 * 1024)
    }

    // La limpieza más importante: /sdcard/Android/data/*/cache/ (WhatsApp, Chrome, Instagram, etc.)
    private fun cleanExternalAppCaches(): Long {
        val androidData = File(Environment.getExternalStorageDirectory(), "Android/data")
        if (!androidData.exists() || !androidData.canRead()) return 0L
        var freed = 0L
        androidData.listFiles()?.forEach { appDir ->
            if (appDir.name == packageName) return@forEach
            try {
                val cache = File(appDir, "cache")
                if (cache.exists() && cache.canRead()) {
                    freed += cache.deleteContentsAndMeasure()
                }
            } catch (_: Exception) {}
        }
        return freed / (1024 * 1024)
    }

    private fun cleanThumbnails(): Long {
        var freed = 0L
        val ext = Environment.getExternalStorageDirectory()
        listOf(
            File(ext, "DCIM/.thumbnails"),
            File(ext, ".thumbnails"),
            File(ext, "Pictures/.thumbnails")
        ).forEach { dir ->
            if (dir.exists()) {
                try { freed += dir.deleteContentsAndMeasure() } catch (_: Exception) {}
            }
        }
        return freed / (1024 * 1024)
    }

    private fun cleanStaleDownloads(): Long {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloads.exists()) return 0L
        val cutoff  = System.currentTimeMillis() - OLD_FILE_DAYS * 24 * 60 * 60 * 1000L
        val minSize = MIN_FILE_SIZE_MB * 1024 * 1024
        var freed   = 0L
        downloads.listFiles()?.forEach { file ->
            if (file.isFile && file.length() >= minSize && file.lastModified() < cutoff) {
                val size = file.length()
                if (file.delete()) freed += size
            }
        }
        return freed / (1024 * 1024)
    }

    private fun killBackgroundApps(): Int {
        val am = getSystemService(ActivityManager::class.java)
        var killed = 0
        packageManager.getInstalledApplications(PackageManager.GET_META_DATA).forEach { app ->
            if (app.packageName != packageName) {
                try { am.killBackgroundProcesses(app.packageName); killed++ } catch (_: Exception) {}
            }
        }
        return killed
    }

    private fun getFreeStorageMb(): Long {
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        return stat.availableBytes / (1024 * 1024)
    }

    private fun File.sizeRecursive(): Long =
        if (isFile) length() else listFiles()?.sumOf { it.sizeRecursive() } ?: 0L

    private fun File.deleteContentsAndMeasure(): Long {
        var freed = 0L
        listFiles()?.forEach { child ->
            freed += child.sizeRecursive()
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
        totalMb: Long, systemMb: Long, extCacheMb: Long, thumbsMb: Long,
        staleMb: Long, killedApps: Int, timestamp: String
    ) {
        val mainIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val summary = if (totalMb > 0) "${totalMb} MB liberados" else "Almacenamiento ya optimizado"
        val detail  = buildString {
            if (extCacheMb > 0) appendLine("- Cache de apps: ${extCacheMb} MB")
            if (systemMb   > 0) appendLine("- Cache del sistema: ${systemMb} MB")
            if (thumbsMb   > 0) appendLine("- Miniaturas: ${thumbsMb} MB")
            if (staleMb    > 0) appendLine("- Descargas viejas: ${staleMb} MB")
            if (killedApps > 0) appendLine("- Procesos detenidos: $killedApps")
            append(timestamp)
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
        nm.createNotificationChannel(NotificationChannel(CHANNEL_PROGRESS,
            "Limpieza en progreso", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_RESULT,
            "Resultado de limpieza", NotificationManager.IMPORTANCE_DEFAULT))
    }
}
