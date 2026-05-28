# Memory Cleaner — Código fuente completo

App Android que limpia automáticamente la caché del sistema y archivos obsoletos.
Limpieza automática: medianoche y 6:30am. Limpieza manual disponible desde la pantalla principal.

---

## Estructura del proyecto

```
MemoryCleaner/
├── .github/workflows/build.yml
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/memorycleaner/
│       │   ├── MainActivity.kt
│       │   ├── CleanupService.kt
│       │   ├── CleanupScheduler.kt
│       │   ├── CleanupReceiver.kt
│       │   └── BootReceiver.kt
│       └── res/
│           ├── layout/activity_main.xml
│           ├── values/strings.xml
│           ├── values/themes.xml
│           └── drawable/
│               ├── ic_launcher_background.xml
│               └── ic_launcher_foreground.xml
├── build.gradle
├── settings.gradle
└── gradle.properties
```

---

## .github/workflows/build.yml

```yaml
name: Build APK

on:
  push:
    branches: [ main ]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Set up Android SDK
        uses: android-actions/setup-android@v3

      - name: Grant execute permission for gradlew
        run: chmod +x gradlew

      - name: Build debug APK
        run: ./gradlew assembleDebug

      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: MemoryCleaner-debug
          path: app/build/outputs/apk/debug/*.apk
          retention-days: 7
```

---

## app/build.gradle

```groovy
plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
}

android {
    namespace 'com.memorycleaner'
    compileSdk 34

    defaultConfig {
        applicationId "com.memorycleaner"
        minSdk 26
        targetSdk 34
        versionCode 1
        versionName "1.0"
    }

    buildTypes {
        release {
            minifyEnabled false
        }
    }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = '1.8'
    }
    buildFeatures {
        viewBinding true
    }
}

dependencies {
    implementation 'androidx.core:core-ktx:1.12.0'
    implementation 'androidx.appcompat:appcompat:1.6.1'
    implementation 'com.google.android.material:material:1.11.0'
    implementation 'androidx.constraintlayout:constraintlayout:2.1.4'
}
```

---

## app/src/main/AndroidManifest.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
    <uses-permission android:name="android.permission.USE_EXACT_ALARM" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
    <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.MemoryCleaner">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <receiver android:name=".CleanupReceiver" android:exported="false" />

        <receiver android:name=".BootReceiver" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.LOCKED_BOOT_COMPLETED" />
            </intent-filter>
        </receiver>

        <service
            android:name=".CleanupService"
            android:exported="false"
            android:foregroundServiceType="dataSync" />

    </application>
</manifest>
```

---

## MainActivity.kt

```kotlin
package com.memorycleaner

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.memorycleaner.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestRuntimePermissions()
        CleanupScheduler.schedule(this)

        binding.btnCleanNow.setOnClickListener {
            ContextCompat.startForegroundService(this, Intent(this, CleanupService::class.java))
            Toast.makeText(this, "Limpieza iniciada...", Toast.LENGTH_SHORT).show()
        }

        binding.btnFixBattery.setOnClickListener { grantBatteryExemption() }
        binding.btnFixStorage.setOnClickListener { grantStorageAccess() }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)

        val stat    = StatFs(Environment.getDataDirectory().absolutePath)
        val totalMb = stat.totalBytes     / (1024 * 1024)
        val freeMb  = stat.availableBytes / (1024 * 1024)
        val usedMb  = totalMb - freeMb
        val usedPct = if (totalMb > 0) usedMb * 100 / totalMb else 0
        binding.tvStorageNow.text =
            "Usado: ${fmtMb(usedMb)} de ${fmtMb(totalMb)} (${usedPct}%)\nLibre: ${fmtMb(freeMb)}"

        val lastRun = prefs.getString("last_run", null)
        val freedMb = prefs.getLong("freed_total_mb", 0)
        val sysMb   = prefs.getLong("freed_system_mb", 0)
        val staleMb = prefs.getLong("freed_stale_mb", 0)
        if (lastRun != null) {
            binding.tvLastRun.text    = "Ultima limpieza: $lastRun"
            binding.tvLastResult.text = buildString {
                append("Liberados: ${fmtMb(freedMb)}")
                if (sysMb   > 0) append("\n  Cache del sistema: ${fmtMb(sysMb)}")
                if (staleMb > 0) append("\n  Archivos viejos en Descargas: ${fmtMb(staleMb)}")
            }
        } else {
            binding.tvLastRun.text    = "Aun no se ha ejecutado ninguna limpieza."
            binding.tvLastResult.text = ""
        }

        val nextRun = prefs.getLong("next_run", 0L)
        binding.tvNextRun.text = if (nextRun > 0) {
            val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            "Proxima limpieza automatica: ${fmt.format(Date(nextRun))}"
        } else "Proxima limpieza automatica: medianoche"

        val pm        = getSystemService(POWER_SERVICE) as android.os.PowerManager
        val batteryOk = pm.isIgnoringBatteryOptimizations(packageName)
        val storageOk = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                        Environment.isExternalStorageManager()

        binding.btnFixBattery.text      = if (batteryOk) "Bateria: OK"
                                          else "Paso 1: Permitir ejecucion en segundo plano"
        binding.btnFixBattery.isEnabled = !batteryOk

        binding.btnFixStorage.text      = if (storageOk) "Almacenamiento: OK"
                                          else "Paso 2: Conceder acceso a archivos"
        binding.btnFixStorage.isEnabled = !storageOk

        binding.tvSetupNote.text = if (batteryOk && storageOk)
            "Configuracion completa. La app limpiara automaticamente a medianoche sin interrumpirte."
        else
            "Completa los pasos anteriores una sola vez para que la limpieza automatica funcione correctamente."
    }

    private fun fmtMb(mb: Long): String =
        if (mb >= 1024) String.format("%.1f GB", mb / 1024.0) else "$mb MB"

    private fun grantBatteryExemption() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            })
        } catch (_: Exception) {
            Toast.makeText(this,
                "Abre Ajustes > Apps > MemoryCleaner > Bateria > Sin restricciones",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun grantStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: Exception) {
                Toast.makeText(this,
                    "Abre Ajustes > Apps > MemoryCleaner > Permisos > Archivos",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun requestRuntimePermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        val pending = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (pending.isNotEmpty())
            ActivityCompat.requestPermissions(this, pending.toTypedArray(), 100)
    }
}
```

---

## CleanupService.kt

```kotlin
package com.memorycleaner

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
        val storageBefore  = getFreeStorageMb()
        val freedBySystem  = requestSystemCacheCleanup()
        val freedByOwn     = cleanOwnCache()
        val freedByStale   = cleanStaleDownloads()

        Thread.sleep(500)
        val storageAfter = getFreeStorageMb()
        val totalFreed   = (storageAfter - storageBefore).coerceAtLeast(0)

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

    private fun getFreeStorageMb(): Long {
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        return stat.availableBytes / (1024 * 1024)
    }

    private fun File.deleteContentsAndMeasure(): Long {
        var freed = 0L
        listFiles()?.forEach { child -> freed += child.length(); child.deleteRecursively() }
        return freed
    }

    private fun buildProgressNotification() =
        NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setContentTitle("Liberando almacenamiento...")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .build()

    private fun showResultNotification(totalMb: Long, systemMb: Long, staleMb: Long, timestamp: String) {
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
        nm.createNotificationChannel(NotificationChannel(CHANNEL_PROGRESS,
            "Limpieza en progreso", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_RESULT,
            "Resultado de limpieza", NotificationManager.IMPORTANCE_DEFAULT))
    }
}
```

---

## CleanupScheduler.kt

```kotlin
package com.memorycleaner

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

object CleanupScheduler {

    // (hora, minuto, requestCode)
    private val SCHEDULES = listOf(
        Triple(0,  0,  1001),   // 00:00 medianoche
        Triple(6, 30, 1002)     // 06:30 manana
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
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
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
```

---

## CleanupReceiver.kt

```kotlin
package com.memorycleaner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class CleanupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ContextCompat.startForegroundService(context, Intent(context, CleanupService::class.java))
        CleanupScheduler.schedule(context)
    }
}
```

---

## BootReceiver.kt

```kotlin
package com.memorycleaner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.LOCKED_BOOT_COMPLETED") {
            CleanupScheduler.schedule(context)
        }
    }
}
```

---

## res/layout/activity_main.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="24dp">

        <TextView
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Storage Cleaner"
            android:textSize="26sp"
            android:textStyle="bold"
            android:layout_marginBottom="24dp" />

        <TextView
            android:id="@+id/tvStorageNow"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textSize="15sp"
            android:padding="12dp"
            android:background="#E3F2FD"
            android:layout_marginBottom="12dp" />

        <TextView
            android:id="@+id/tvNextRun"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textSize="14sp"
            android:layout_marginBottom="6dp" />

        <TextView
            android:id="@+id/tvLastRun"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textSize="14sp"
            android:layout_marginBottom="4dp" />

        <TextView
            android:id="@+id/tvLastResult"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textSize="13sp"
            android:textColor="#555555"
            android:layout_marginBottom="24dp" />

        <Button
            android:id="@+id/btnCleanNow"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="Limpiar ahora"
            android:textSize="16sp"
            android:padding="14dp"
            android:layout_marginBottom="32dp" />

        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="Configuracion inicial (solo una vez)"
            android:textSize="14sp"
            android:textStyle="bold"
            android:layout_marginBottom="8dp" />

        <TextView
            android:id="@+id/tvSetupNote"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textSize="13sp"
            android:textColor="#555555"
            android:layout_marginBottom="12dp" />

        <Button
            android:id="@+id/btnFixBattery"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textSize="14sp"
            android:layout_marginBottom="8dp" />

        <Button
            android:id="@+id/btnFixStorage"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:textSize="14sp" />

    </LinearLayout>
</ScrollView>
```

---

## res/values/strings.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Memory Cleaner</string>
</resources>
```

---

## res/values/themes.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.MemoryCleaner" parent="Theme.MaterialComponents.DayNight.DarkActionBar">
        <item name="colorPrimary">#1565C0</item>
        <item name="colorPrimaryVariant">#003c8f</item>
        <item name="colorOnPrimary">#FFFFFF</item>
    </style>
</resources>
```

---

## res/drawable/ic_launcher_background.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android">
    <solid android:color="#1976D2"/>
</shape>
```

## res/drawable/ic_launcher_foreground.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <!-- Gota de agua (limpieza) -->
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M54,20 C54,20 36,38 36,56 C36,65.9 44.1,74 54,74 C63.9,74 72,65.9 72,56 C72,38 54,20 54,20 Z"/>
    <!-- Base de la gota -->
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M46,74 L62,74 L65,88 L43,88 Z"/>
</vector>
```

---

## Notas de compilación

**Método:** GitHub Actions (el único método aprobado para este proyecto).

**Pasos:**
1. `git push` a rama `main`
2. GitHub Actions ejecuta `build.yml` automáticamente
3. Descargar el APK desde la pestaña **Actions → MemoryCleaner-debug**
4. Instalar en el teléfono físico

**Funcionalidades:**
- Limpieza automática: medianoche (00:00) y mañana (06:30)
- Limpieza manual con botón
- Limpia: caché del sistema (via `StorageManager`), caché propia de la app, archivos de Descargas >30 días y >10 MB
- Sobrevive reinicios del teléfono (`BootReceiver`)
- Notificación de progreso + resultado con MB liberados
- Pantalla de configuración inicial guiada (batería + almacenamiento)
