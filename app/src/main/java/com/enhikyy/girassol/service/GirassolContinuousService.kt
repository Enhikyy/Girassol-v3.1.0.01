package com.enhikyy.girassol.service

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.enhikyy.girassol.MainActivity
import com.enhikyy.girassol.R
import com.enhikyy.girassol.sim.SimulationHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Serviço em primeiro plano (tipo specialUse) que mantém o mundo vivo 24/7.
 * Usa PARTIAL_WAKE_LOCK renovado periodicamente, escuta o status térmico do
 * PowerManager e alimenta a telemetria (térmico, RAM, bateria) do WorldCore.
 */
class GirassolContinuousService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var monitorJob: Job? = null
    private val thermalListener = PowerManager.OnThermalStatusChangedListener { pushTelemetry(it) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification("Iniciando o mundo…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        val pm = getSystemService(PowerManager::class.java)
        pm.addThermalStatusListener(mainExecutor, thermalListener)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Girassol:WorldCore").apply { setReferenceCounted(false) }
        SimulationHub.ensureLoaded(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            SimulationHub.pause()
            stopSelf()
            return START_NOT_STICKY
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, true).apply()
        SimulationHub.start()
        if (monitorJob?.isActive != true) monitorJob = scope.launch { monitor() }
        return START_STICKY
    }

    private suspend fun monitor() {
        // aguarda o carregamento e garante que o loop está rodando
        while (scope.isActive && !SimulationHub.status.value.loaded) delay(200)
        SimulationHub.start()
        val nm = getSystemService(NotificationManager::class.java)
        while (scope.isActive) {
            // wake lock com timeout explícito, renovado a cada ciclo (nunca indefinido)
            wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS)
            pushTelemetry(getSystemService(PowerManager::class.java).currentThermalStatus)
            val r = SimulationHub.render.value
            val st = SimulationHub.status.value
            val text = if (r == null) "Carregando…" else
                "Dia ${r.day} · tick ${r.tick} · ${r.population} cidadãos · ${"%.1f".format(st.effectiveTps)} t/s" +
                    if (st.thermalCapped) " · térmico" else ""
            nm.notify(NOTIFICATION_ID, buildNotification(text))
            delay(5_000)
        }
    }

    private fun pushTelemetry(thermalStatus: Int) {
        val am = getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val bm = getSystemService(BatteryManager::class.java)
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        SimulationHub.updateTelemetry(thermalStatus, mi.availMem / (1024 * 1024), pct, bm.isCharging)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, GirassolContinuousService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_girassol)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_pause), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    override fun onDestroy() {
        runCatching { getSystemService(PowerManager::class.java).removeThermalStatusListener(thermalListener) }
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        SimulationHub.flush()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "girassol_mundo"
        const val NOTIFICATION_ID = 31001
        const val ACTION_STOP = "com.enhikyy.girassol.action.STOP"
        const val PREFS = "girassol_service"
        const val KEY_RUNNING = "running"
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L

        fun createChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW).apply { description = context.getString(R.string.channel_description) })
        }

        fun start(context: Context) {
            context.startForegroundService(Intent(context, GirassolContinuousService::class.java))
        }

        fun stop(context: Context) {
            context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, false).apply()
            context.startService(Intent(context, GirassolContinuousService::class.java).setAction(ACTION_STOP))
        }

        fun shouldAutoStart(context: Context): Boolean =
            context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_RUNNING, false)
    }
}
