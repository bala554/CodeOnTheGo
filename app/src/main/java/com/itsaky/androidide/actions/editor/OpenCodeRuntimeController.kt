package com.itsaky.androidide.actions.editor

import android.app.Activity
import com.itsaky.androidide.app.PluginTerminalLauncher
import com.itsaky.androidide.plugins.manager.services.LaunchedTerminalCommand
import com.itsaky.androidide.plugins.manager.services.TerminalSessionLauncher
import com.itsaky.androidide.plugins.services.TerminalCommandResult
import com.itsaky.androidide.utils.Environment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

internal sealed interface OpenCodeServerStatus {
    data object NotInstalled : OpenCodeServerStatus
    data object Stopped : OpenCodeServerStatus
    data object Starting : OpenCodeServerStatus
    data class Running(val version: String, val managedByThisApp: Boolean) : OpenCodeServerStatus
    data class VersionConflict(val version: String) : OpenCodeServerStatus
    data class Failed(val message: String) : OpenCodeServerStatus
}

/** Installs the runtime inside the app, then keeps the local server in a user-visible Terminal session. */
internal class OpenCodeRuntimeController(
    private val installer: OpenCodeRuntimeInstaller,
    private val launcherFactory: (Activity) -> TerminalSessionLauncher = { activity ->
        PluginTerminalLauncher(foregroundActivity = { activity })
    },
    private val healthProbe: suspend () -> String? = { readHealthVersion() },
) {
    suspend fun status(): OpenCodeServerStatus {
        if (!installer.isSupportedDevice) {
            return OpenCodeServerStatus.Failed("The OpenCode runtime requires a 64-bit ARM Android device")
        }
        val installedVersion = installer.installedVersion() ?: return OpenCodeServerStatus.NotInstalled
        val serverVersion = healthProbe() ?: return if (OpenCodeTerminalSession.command?.result?.isCompleted == false) {
            OpenCodeServerStatus.Starting
        } else {
            OpenCodeServerStatus.Stopped
        }
        if (serverVersion != OpenCodeRuntimeInstaller.VERSION) return OpenCodeServerStatus.VersionConflict(serverVersion)
        val managed = OpenCodeTerminalSession.command?.snapshot() != null
        return OpenCodeServerStatus.Running(installedVersion, managed)
    }

    suspend fun install(
        onProgress: suspend (OpenCodeInstallProgress) -> Unit = {},
    ): OpenCodeInstallation = OpenCodeTerminalSession.mutex.withLock {
        if (!installer.isSupportedDevice) throw IOException("The OpenCode runtime requires a 64-bit ARM Android device")
        if (OpenCodeTerminalSession.command?.result?.isCompleted == false || healthProbe() != null) {
            throw IOException("Stop the local OpenCode server before reinstalling its runtime")
        }
        installer.install(onProgress)
    }

    suspend fun start(
        activity: Activity,
        projectDirectory: File?,
    ): OpenCodeServerStatus = OpenCodeTerminalSession.mutex.withLock {
        if (!installer.isSupportedDevice) {
            return OpenCodeServerStatus.Failed("The OpenCode runtime requires a 64-bit ARM Android device")
        }
        val version = installer.installedVersion() ?: return OpenCodeServerStatus.NotInstalled
        val activeVersion = healthProbe()
        if (activeVersion != null) {
            return if (activeVersion == OpenCodeRuntimeInstaller.VERSION) {
                OpenCodeServerStatus.Running(version, OpenCodeTerminalSession.command?.snapshot() != null)
            } else {
                OpenCodeServerStatus.VersionConflict(activeVersion)
            }
        }
        val previous = OpenCodeTerminalSession.command
        if (previous?.result?.isCompleted == true) OpenCodeTerminalSession.command = null
        if (previous?.result?.isCompleted == false) return OpenCodeServerStatus.Starting
        if (projectDirectory != null && !projectDirectory.isDirectory) {
            return OpenCodeServerStatus.Failed("The selected project directory does not exist")
        }
        val proot = File(Environment.PREFIX, "bin/proot")
        val runtime = installer.runtimeDirectory()
        val command = try {
            buildServerCommand(proot, File(runtime, "rootfs"), projectDirectory, File(Environment.PREFIX, "etc/resolv.conf"))
        } catch (error: Exception) {
            return OpenCodeServerStatus.Failed(error.message ?: "Could not prepare the Proot launch command")
        }
        val launched = try {
            launcherFactory(activity).launch(command, projectDirectory, OWNER_ID, SESSION_LABEL)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return OpenCodeServerStatus.Failed(error.message ?: "Could not open the CodeOnTheGo Terminal")
        }
        OpenCodeTerminalSession.command = launched
        val started = withTimeoutOrNull(SESSION_START_TIMEOUT_MS) {
            select<Boolean> {
                launched.started.onAwait { true }
                launched.result.onAwait { false }
            }
        }
        if (started != true) {
            val result = withTimeoutOrNull(RESULT_READ_TIMEOUT_MS) { launched.result.await() }
            if (result == null) {
                launched.interrupt()
                return OpenCodeServerStatus.Failed("The visible Terminal session did not start")
            }
            OpenCodeTerminalSession.command = null
            return OpenCodeServerStatus.Failed((result as? TerminalCommandResult.NotStarted)?.reason ?: "The visible Terminal session did not start OpenCode")
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SERVER_START_TIMEOUT_MS)
        while (System.nanoTime() < deadline) {
            val healthVersion = healthProbe()
            if (healthVersion != null) {
                return if (healthVersion == OpenCodeRuntimeInstaller.VERSION) {
                    OpenCodeServerStatus.Running(version, managedByThisApp = true)
                } else {
                    OpenCodeServerStatus.VersionConflict(healthVersion)
                }
            }
            if (launched.result.isCompleted) {
                OpenCodeTerminalSession.command = null
                val result = runCatching { launched.result.await() }.getOrNull()
                val output = (result as? TerminalCommandResult.Completed)?.output.orEmpty().takeLast(400).trim()
                return OpenCodeServerStatus.Failed(output.ifBlank { "OpenCode exited before its local server became healthy" })
            }
            delay(HEALTH_POLL_INTERVAL_MS)
        }
        OpenCodeServerStatus.Starting
    }

    suspend fun stop(): OpenCodeServerStatus = OpenCodeTerminalSession.mutex.withLock {
        val serverVersion = healthProbe()
        val command = OpenCodeTerminalSession.command
        if (serverVersion == null) {
            if (command == null || command.result.isCompleted) {
                OpenCodeTerminalSession.command = null
                return OpenCodeServerStatus.Stopped
            }
            command.interrupt()
            val result = withTimeoutOrNull(SERVER_STOP_TIMEOUT_MS) { command.result.await() }
            if (result != null) {
                OpenCodeTerminalSession.command = null
                return OpenCodeServerStatus.Stopped
            }
            return OpenCodeServerStatus.Starting
        }
        if (serverVersion != OpenCodeRuntimeInstaller.VERSION) return OpenCodeServerStatus.VersionConflict(serverVersion)
        if (command == null || command.snapshot() == null) return OpenCodeServerStatus.Running(serverVersion, managedByThisApp = false)
        command.interrupt()
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SERVER_STOP_TIMEOUT_MS)
        while (System.nanoTime() < deadline) {
            if (healthProbe() == null) {
                OpenCodeTerminalSession.command = null
                return OpenCodeServerStatus.Stopped
            }
            delay(HEALTH_POLL_INTERVAL_MS)
        }
        val remaining = healthProbe()
        if (remaining == null) {
            OpenCodeTerminalSession.command = null
            OpenCodeServerStatus.Stopped
        } else {
            OpenCodeServerStatus.Running(remaining, OpenCodeTerminalSession.command?.snapshot() != null)
        }
    }

    companion object {
        const val HOST = OpenCodeRuntimeInstaller.HOST
        const val PORT = OpenCodeRuntimeInstaller.PORT
        const val HEALTH_URL = "http://127.0.0.1:4098/global/health"
        internal const val CHAT_MODE_CONFIG = """{"agent":{"plan":{"permission":{"*":"deny","read":"allow","glob":"allow","grep":"allow","question":"allow","edit":"deny","write":"deny","patch":"deny","bash":"deny","task":"deny","external_directory":"deny"}},"build":{"permission":{"read":"allow","edit":"allow","write":"allow","patch":"allow"}}}}"""
        private const val OWNER_ID = "builtin.opencode.local"
        private const val SESSION_LABEL = "OpenCode Server"
        private const val SESSION_START_TIMEOUT_MS = 20_000L
        private const val RESULT_READ_TIMEOUT_MS = 1_000L
        private const val SERVER_START_TIMEOUT_MS = 60_000L
        private const val SERVER_STOP_TIMEOUT_MS = 12_000L
        private const val HEALTH_POLL_INTERVAL_MS = 800L
        private const val HEALTH_TIMEOUT_MS = 1_200

        internal fun buildServerCommand(
            proot: File,
            rootfs: File,
            projectDirectory: File?,
            resolvConf: File?,
            prefix: File = Environment.PREFIX,
        ): String {
            if (!proot.canExecute()) throw IOException("The Proot executable is not installed")
            if (!rootfs.isDirectory) throw IOException("The Debian rootfs is not installed")
            if (projectDirectory != null && !projectDirectory.isDirectory) {
                throw IOException("The selected project directory does not exist")
            }
            val project = projectDirectory?.canonicalPath
            if (project?.contains(':') == true) throw IOException("Project paths containing ':' cannot be mounted into the Debian rootfs")
            val guestWorkingDirectory = if (project == null) "/root" else "/workspace/project"
            val bindProject = project?.let { " -b ${"$it:/workspace/project".shellQuote()}" }.orEmpty()
            val bindResolver = resolvConf?.takeIf { it.isFile }?.let { " -b ${"${it.absolutePath}:/etc/resolv.conf".shellQuote()}" }.orEmpty()
            return buildString {
                append("export PREFIX=").append(prefix.absolutePath.shellQuote()).append("; ")
                append("export PATH=").append("${File(prefix, "bin").absolutePath}:/system/bin:/system/xbin".shellQuote()).append("; ")
                append("export LD_LIBRARY_PATH=").append(File(prefix, "lib").absolutePath.shellQuote()).append("; ")
                append("export TMPDIR=").append(File(prefix, "tmp").absolutePath.shellQuote()).append("; ")
                append("export PROOT_TMP_DIR=").append(File(prefix, "tmp").absolutePath.shellQuote()).append("; ")
                append("unset OPENCODE_SERVER_PASSWORD OPENCODE_SERVER_USERNAME; exec ")
                append(proot.absolutePath.shellQuote())
                append(" --link2symlink -0 -r ").append(rootfs.absolutePath.shellQuote())
                append(" -b /dev -b /proc -b /sys")
                append(bindResolver).append(bindProject)
                append(" -w ").append(guestWorkingDirectory.shellQuote())
                append(" /usr/bin/env -u LD_LIBRARY_PATH -u OPENCODE_SERVER_PASSWORD -u OPENCODE_SERVER_USERNAME")
                append(" HOME=/root PATH=/usr/local/bin:/usr/bin:/bin TMPDIR=/tmp TERM=xterm-256color")
                append(" OPENCODE_CONFIG_CONTENT=").append(CHAT_MODE_CONFIG.shellQuote())
                append(" /usr/local/bin/opencode serve --hostname ").append(HOST).append(" --port ").append(PORT)
            }
        }

        private suspend fun readHealthVersion(): String? = withContext(Dispatchers.IO) {
            val connection = (URL(HEALTH_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = HEALTH_TIMEOUT_MS
                readTimeout = HEALTH_TIMEOUT_MS
                instanceFollowRedirects = false
            }
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
                val response = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                if (!response.optBoolean("healthy")) null else response.optString("version").takeIf { it.isNotBlank() && it != "null" }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } finally {
                connection.disconnect()
            }
        }

        private fun String.shellQuote(): String = "'${replace("'", "'\\''")}'"
    }
}

/** Tracks only the command started by this controller, never an arbitrary process or PID. */
private object OpenCodeTerminalSession {
    val mutex = Mutex()

    @Volatile
    var command: LaunchedTerminalCommand? = null
}
