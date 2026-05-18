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

        // Solicitar permisos de notificacion y almacenamiento (solo primera vez)
        requestRuntimePermissions()

        // Programar alarma diaria si no esta activa
        CleanupScheduler.schedule(this)

        // Limpieza manual
        binding.btnCleanNow.setOnClickListener {
            ContextCompat.startForegroundService(this, Intent(this, CleanupService::class.java))
            Toast.makeText(this, "Limpieza iniciada...", Toast.LENGTH_SHORT).show()
        }

        // Botones de configuracion unica — el usuario los toca una sola vez al instalar
        binding.btnFixBattery.setOnClickListener { grantBatteryExemption() }
        binding.btnFixStorage.setOnClickListener { grantStorageAccess() }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val prefs = getSharedPreferences("prefs", Context.MODE_PRIVATE)

        // Uso de almacenamiento actual
        val stat    = StatFs(Environment.getDataDirectory().absolutePath)
        val totalMb = stat.totalBytes    / (1024 * 1024)
        val freeMb  = stat.availableBytes / (1024 * 1024)
        val usedMb  = totalMb - freeMb
        val usedPct = if (totalMb > 0) usedMb * 100 / totalMb else 0
        binding.tvStorageNow.text =
            "Usado: ${fmtMb(usedMb)} de ${fmtMb(totalMb)} (${usedPct}%)\nLibre: ${fmtMb(freeMb)}"

        // Resultado de la ultima limpieza
        val lastRun  = prefs.getString("last_run", null)
        val freedMb  = prefs.getLong("freed_total_mb", 0)
        val sysMb    = prefs.getLong("freed_system_mb", 0)
        val staleMb  = prefs.getLong("freed_stale_mb", 0)
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

        // Proxima ejecucion automatica
        val nextRun = prefs.getLong("next_run", 0L)
        binding.tvNextRun.text = if (nextRun > 0) {
            val fmt = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            "Proxima limpieza automatica: ${fmt.format(Date(nextRun))}"
        } else {
            "Proxima limpieza automatica: medianoche"
        }

        // Estado de los dos permisos criticos para el funcionamiento automatico
        val pm           = getSystemService(POWER_SERVICE) as android.os.PowerManager
        val batteryOk    = pm.isIgnoringBatteryOptimizations(packageName)
        val storageOk    = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                           Environment.isExternalStorageManager()

        binding.btnFixBattery.text = if (batteryOk)
            "Bateria: OK" else "Paso 1: Permitir ejecucion en segundo plano"
        binding.btnFixBattery.isEnabled = !batteryOk

        binding.btnFixStorage.text = if (storageOk)
            "Almacenamiento: OK" else "Paso 2: Conceder acceso a archivos"
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
            Toast.makeText(this, "Abre Ajustes > Apps > MemoryCleaner > Bateria > Sin restricciones",
                Toast.LENGTH_LONG).show()
        }
    }

    private fun grantStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: Exception) {
                Toast.makeText(this, "Abre Ajustes > Apps > MemoryCleaner > Permisos > Archivos",
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
