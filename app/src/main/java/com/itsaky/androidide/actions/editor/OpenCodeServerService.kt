package com.itsaky.androidide.actions.editor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.itsaky.androidide.R
import com.itsaky.androidide.resources.R as StringsR
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile

/** Owns the local OpenCode process independently of the editor and its Terminal UI. */
class OpenCodeServerService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val launchIntent = intent ?: run {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        when (launchIntent.action) {
            ACTION_STOP -> {
                stopManagedProcess()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val command = launchIntent.getStringExtra(EXTRA_COMMAND)?.takeIf { it.isNotBlank() }
                if (command == null) {
                    stopSelfResult(startId)
                    return START_NOT_STICKY
                }
                ensureNotificationChannel()
                startForeground(NOTIFICATION_ID, buildNotification())
                synchronized(processLock) {
                    if (serviceProcess?.isAlive == true || serverJob?.isActive == true) {
                        return START_REDELIVER_INTENT
                    }
                    isLaunching = true
                    lastFailure = null
                    serverJob = serviceScope.launch { runServer(command, startId) }
                }
                return START_REDELIVER_INTENT
            }
            else -> {
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
        }
    }

    private suspend fun runServer(command: String, startId: Int) {
        var process: Process? = null
        try {
            val log = logFile(this)
            if (!log.parentFile?.exists().orFalse() && log.parentFile?.mkdirs() != true) {
                throw IllegalStateException("Could not create the private OpenCode log directory")
            }
            val runningProcess = ProcessBuilder("/system/bin/sh", "-c", command)
                .directory(filesDir)
                .redirectErrorStream(true)
                .redirectOutput(log)
                .start()
            process = runningProcess
            synchronized(processLock) {
                serviceProcess = runningProcess
                isLaunching = false
            }
            val exitCode = runningProcess.waitFor()
            val output = readLogTail(log)
            synchronized(processLock) {
                if (serviceProcess === runningProcess) serviceProcess = null
                isLaunching = false
                lastFailure = buildString {
                    append("OpenCode exited (code ").append(exitCode).append(')')
                    if (output.isNotBlank()) append(": ").append(output)
                }
            }
        } catch (cancelled: CancellationException) {
            process?.let(::terminate)
            throw cancelled
        } catch (error: Exception) {
            synchronized(processLock) {
                isLaunching = false
                if (serviceProcess === process) serviceProcess = null
                lastFailure = error.message ?: "Could not start the local OpenCode server"
            }
        } finally {
            synchronized(processLock) {
                if (serviceProcess === process) serviceProcess = null
                isLaunching = false
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
        }
    }

    private fun stopManagedProcess() {
        serverJob?.cancel()
        serverJob = null
        synchronized(processLock) {
            isLaunching = false
            serviceProcess?.let(::terminate)
            serviceProcess = null
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        synchronized(processLock) {
            isLaunching = false
            serviceProcess?.let(::terminate)
            serviceProcess = null
        }
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                REQUEST_OPEN_APP,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val stopIntent = Intent(this, OpenCodeServerService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            REQUEST_STOP_SERVER,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ai)
            .setContentTitle(getString(StringsR.string.opencode_service_notification_title))
            .setContentText(getString(StringsR.string.opencode_service_notification_text))
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_media_pause, getString(StringsR.string.opencode_service_stop), stopPendingIntent)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        NOTIFICATION_CHANNEL_ID,
                        getString(StringsR.string.opencode_service_channel_name),
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply { description = getString(StringsR.string.opencode_service_channel_description) },
                )
            }
        }
    }

    companion object {
        private const val ACTION_START = "com.itsaky.androidide.opencode.START_SERVER"
        private const val ACTION_STOP = "com.itsaky.androidide.opencode.STOP_SERVER"
        private const val EXTRA_COMMAND = "opencode.command"
        private const val NOTIFICATION_CHANNEL_ID = "opencode_local_server"
        private const val NOTIFICATION_ID = 4098
        private const val REQUEST_OPEN_APP = 4098
        private const val REQUEST_STOP_SERVER = 4099

        private val processLock = Any()

        @Volatile
        private var serviceProcess: Process? = null

        @Volatile
        private var isLaunching = false

        @Volatile
        private var lastFailure: String? = null

        fun start(context: Context, command: String) {
            val intent = Intent(context, OpenCodeServerService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_COMMAND, command)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OpenCodeServerService::class.java))
        }

        fun isManagedActive(): Boolean = serviceProcess?.isAlive == true

        fun isStartingOrRunning(): Boolean = isLaunching || isManagedActive()

        fun lastFailureMessage(): String? = lastFailure

        fun logFile(context: Context): File = File(context.filesDir, "opencode/server.log")

        private fun terminate(process: Process) {
            if (!process.isAlive) return
            process.destroy()
            Thread({
                try {
                    if (!process.waitFor(PROCESS_GRACE_SECONDS, java.util.concurrent.TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    process.destroyForcibly()
                }
            }, "OpenCode-server-stop").apply { isDaemon = true }.start()
        }

        private fun readLogTail(file: File): String = runCatching {
            RandomAccessFile(file, "r").use { input ->
                val start = (input.length() - MAX_LOG_TAIL_BYTES).coerceAtLeast(0L)
                input.seek(start)
                ByteArray((input.length() - start).toInt()).also(input::readFully)
                    .toString(Charsets.UTF_8)
                    .trim()
                    .takeLast(MAX_LOG_TAIL_CHARS)
            }
        }.getOrDefault("")

        private fun Boolean?.orFalse(): Boolean = this ?: false

        private const val PROCESS_GRACE_SECONDS = 2L
        private const val MAX_LOG_TAIL_BYTES = 16_384L
        private const val MAX_LOG_TAIL_CHARS = 2_000
    }
}
