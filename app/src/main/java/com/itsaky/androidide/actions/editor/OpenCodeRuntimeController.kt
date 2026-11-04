package com.itsaky.androidide.actions.editor

import android.app.Activity
import android.content.Context
import com.itsaky.androidide.utils.Environment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

internal interface OpenCodeServerHost {
    fun start(context: Context, command: String)
    fun stop(context: Context)
    fun isManagedActive(): Boolean
    fun isStartingOrRunning(): Boolean
    fun lastFailureMessage(): String?
}

private object AndroidOpenCodeServerHost : OpenCodeServerHost {
    override fun start(context: Context, command: String) = OpenCodeServerService.start(context, command)

    override fun stop(context: Context) = OpenCodeServerService.stop(context)

    override fun isManagedActive(): Boolean = OpenCodeServerService.isManagedActive()

    override fun isStartingOrRunning(): Boolean = OpenCodeServerService.isStartingOrRunning()

    override fun lastFailureMessage(): String? = OpenCodeServerService.lastFailureMessage()
}

/** Installs the runtime in the app sandbox and starts its local server in a persistent foreground service. */
internal class OpenCodeRuntimeController(
    private val installer: OpenCodeRuntimeInstaller,
    private val serverHost: OpenCodeServerHost = AndroidOpenCodeServerHost,
    private val healthProbe: suspend () -> String? = { readHealthVersion() },
) {
    suspend fun status(): OpenCodeServerStatus {
        if (!installer.isSupportedDevice) {
            return OpenCodeServerStatus.Failed("The OpenCode runtime requires a 64-bit ARM Android device")
        }
        val installedVersion = installer.installedVersion() ?: return OpenCodeServerStatus.NotInstalled
        val serverVersion = healthProbe() ?: return if (serverHost.isStartingOrRunning()) {
            OpenCodeServerStatus.Starting
        } else {
            OpenCodeServerStatus.Stopped
        }
        if (serverVersion != OpenCodeRuntimeInstaller.VERSION) return OpenCodeServerStatus.VersionConflict(serverVersion)
        return OpenCodeServerStatus.Running(installedVersion, serverHost.isManagedActive())
    }

    suspend fun install(
        onProgress: suspend (OpenCodeInstallProgress) -> Unit = {},
    ): OpenCodeInstallation = OpenCodeServerSession.mutex.withLock {
        if (!installer.isSupportedDevice) throw IOException("The OpenCode runtime requires a 64-bit ARM Android device")
        if (serverHost.isStartingOrRunning() || healthProbe() != null) {
            throw IOException("Stop the local OpenCode server before reinstalling its runtime")
        }
        installer.install(onProgress)
    }

    suspend fun start(
        activity: Activity,
        projectDirectory: File?,
    ): OpenCodeServerStatus = OpenCodeServerSession.mutex.withLock {
        if (!installer.isSupportedDevice) {
            return OpenCodeServerStatus.Failed("The OpenCode runtime requires a 64-bit ARM Android device")
        }
        val version = installer.installedVersion() ?: return OpenCodeServerStatus.NotInstalled
        val activeVersion = healthProbe()
        if (activeVersion != null) {
            return if (activeVersion == OpenCodeRuntimeInstaller.VERSION) {
                OpenCodeServerStatus.Running(version, serverHost.isManagedActive())
            } else {
                OpenCodeServerStatus.VersionConflict(activeVersion)
            }
        }
        if (serverHost.isStartingOrRunning()) return OpenCodeServerStatus.Starting
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
        try {
            serverHost.start(activity, command)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return OpenCodeServerStatus.Failed(error.message ?: "Could not start the OpenCode service")
        }

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SERVER_START_TIMEOUT_MS)
        while (System.nanoTime() < deadline) {
            val healthVersion = healthProbe()
            if (healthVersion != null) {
                return if (healthVersion == OpenCodeRuntimeInstaller.VERSION) {
                    OpenCodeServerStatus.Running(version, managedByThisApp = serverHost.isManagedActive())
                } else {
                    OpenCodeServerStatus.VersionConflict(healthVersion)
                }
            }
            if (!serverHost.isStartingOrRunning()) {
                return OpenCodeServerStatus.Failed(
                    serverHost.lastFailureMessage() ?: "OpenCode exited before its local server became healthy",
                )
            }
            delay(HEALTH_POLL_INTERVAL_MS)
        }
        OpenCodeServerStatus.Starting
    }

    suspend fun stop(activity: Activity): OpenCodeServerStatus = OpenCodeServerSession.mutex.withLock {
        val serverVersion = healthProbe()
        if (serverVersion != null && serverVersion != OpenCodeRuntimeInstaller.VERSION) {
            return OpenCodeServerStatus.VersionConflict(serverVersion)
        }
        if (!serverHost.isStartingOrRunning()) {
            return if (serverVersion == null) {
                OpenCodeServerStatus.Stopped
            } else {
                OpenCodeServerStatus.Running(serverVersion, managedByThisApp = false)
            }
        }
        serverHost.stop(activity)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SERVER_STOP_TIMEOUT_MS)
        while (System.nanoTime() < deadline) {
            val currentVersion = healthProbe()
            if (currentVersion != null && currentVersion != OpenCodeRuntimeInstaller.VERSION) {
                return OpenCodeServerStatus.VersionConflict(currentVersion)
            }
            if (currentVersion == null && !serverHost.isStartingOrRunning()) {
                return OpenCodeServerStatus.Stopped
            }
            delay(HEALTH_POLL_INTERVAL_MS)
        }
        val remaining = healthProbe()
        when {
            remaining == null && !serverHost.isStartingOrRunning() -> OpenCodeServerStatus.Stopped
            remaining != null && remaining != OpenCodeRuntimeInstaller.VERSION -> OpenCodeServerStatus.VersionConflict(remaining)
            remaining != null -> OpenCodeServerStatus.Running(remaining, serverHost.isManagedActive())
            else -> OpenCodeServerStatus.Starting
        }
    }

    companion object {
        const val HOST = OpenCodeRuntimeInstaller.HOST
        const val PORT = OpenCodeRuntimeInstaller.PORT
        const val HEALTH_URL = "http://127.0.0.1:4098/global/health"

        // Enforce Planning read-only at the tool layer; tools not explicitly allowed stay denied.
        internal const val CHAT_MODE_CONFIG = """{"agent":{"plan":{"permission":{"*":"deny","read":"allow","glob":"allow","grep":"allow","question":"allow","edit":"deny","write":"deny","patch":"deny","bash":"deny","task":"deny","external_directory":"deny"}},"build":{"permission":{"read":"allow","edit":"allow","write":"allow","patch":"allow"}}}}"""

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

private object OpenCodeServerSession {
    val mutex = Mutex()
}
