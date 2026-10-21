package com.itsaky.androidide.agent.opencode.service

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.itsaky.androidide.agent.opencode.model.AgentCapability
import com.itsaky.androidide.agent.opencode.model.AgentServerManifest
import com.itsaky.androidide.agent.opencode.model.BinaryDistribution
import com.itsaky.androidide.agent.opencode.model.ProcessState
import com.itsaky.androidide.agent.opencode.model.ServerType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Manages installation, process lifecycle and health of the local OpenCode agent server.
 *
 * Differences from the previous version:
 *  - the manifest is read from assets (single source of truth) instead of being duplicated
 *  - installation really downloads over HTTPS, verifies SHA-256 and extracts safely
 *  - starting the server never reports "Running" unless the health check actually passed
 *  - health requires a 2xx response (404 no longer counts as healthy)
 *  - no wildcard CORS by default
 */
class OpenCodeProcessManager(
    private val context: Context,
    private val coroutineScope: CoroutineScope
) {
    companion object {
        private const val TAG = "OpenCodeProcManager"
        const val DEFAULT_PORT = 4098
        private const val MANIFEST_ASSET = "agents/opencode-manifest.json"
        private const val PREFS_NAME = "opencode_process_manager_prefs"
        private const val KEY_IS_INSTALLED = "is_agent_installed"
        private const val KEY_INSTALLED_VERSION = "installed_version"
        private const val KEY_ASSIGNED_PORT = "assigned_port"
        private const val START_TIMEOUT_MS = 15_000L
        private const val MAX_HEALTH_FAILURES = 3
        private val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")
    }

    private val prefs by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    val manifest: AgentServerManifest = loadManifest()

    val runtimeDir: File = File(context.filesDir, "opencode-runtime")
    private val stagingDir: File = File(context.filesDir, "opencode-runtime.staging")

    val executableFile: File
        get() = File(runtimeDir, selectDistribution()?.executablePath ?: "bin/opencode")
    val binDir: File
        get() = executableFile.parentFile ?: runtimeDir

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followSslRedirects(false) // https -> https only; never downgrade to http
        .build()

    private val _processState = MutableStateFlow<ProcessState>(ProcessState.Available)
    val processState: StateFlow<ProcessState> = _processState.asStateFlow()

    private var runningProcess: Process? = null
    private var healthCheckJob: Job? = null
    private val outputTail = ArrayDeque<String>()

    init {
        coroutineScope.launch(Dispatchers.IO) { checkInstallationAndStatus() }
    }

    // ---- manifest --------------------------------------------------------------------------

    private fun loadManifest(): AgentServerManifest = try {
        context.assets.open(MANIFEST_ASSET).bufferedReader().use { reader ->
            parseManifest(JsonParser.parseString(reader.readText()).asJsonObject)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not read $MANIFEST_ASSET, using built-in defaults", e)
        AgentServerManifest()
    }

    private fun parseManifest(o: JsonObject): AgentServerManifest {
        val d = AgentServerManifest()
        fun str(obj: JsonObject, k: String): String? =
            obj.get(k)?.takeIf { it.isJsonPrimitive }?.asString

        val distribution = o.getAsJsonObject("distribution")?.entrySet()?.mapNotNull { (key, v) ->
            val dist = v.asJsonObject
            val url = str(dist, "url") ?: return@mapNotNull null
            val sha = str(dist, "sha256") ?: return@mapNotNull null
            val exe = str(dist, "executable_path") ?: "bin/opencode"
            key to BinaryDistribution(url, sha, exe)
        }?.toMap() ?: d.distribution

        return d.copy(
            id = str(o, "id") ?: d.id,
            name = str(o, "name") ?: d.name,
            version = str(o, "version") ?: d.version,
            description = str(o, "description") ?: d.description,
            serverType = runCatching { ServerType.valueOf(str(o, "server_type")!!.uppercase()) }
                .getOrDefault(d.serverType),
            defaultPort = o.get("default_port")?.asInt ?: d.defaultPort,
            dynamicPortAllowed = o.get("dynamic_port_allowed")?.asBoolean ?: d.dynamicPortAllowed,
            healthCheckEndpoint = str(o, "health_check_endpoint") ?: d.healthCheckEndpoint,
            capabilities = o.getAsJsonArray("capabilities")?.mapNotNull {
                runCatching { AgentCapability.valueOf(it.asString.uppercase()) }.getOrNull()
            } ?: d.capabilities,
            distribution = distribution,
            spawnArguments = o.getAsJsonArray("spawn_arguments")?.map { it.asString }
                ?: listOf("serve", "--port", "\${PORT}"),
            environmentVariables = o.getAsJsonObject("environment_variables")
                ?.entrySet()?.associate { (k, v) -> k to v.asString } ?: d.environmentVariables,
            acpArguments = o.getAsJsonArray("acp_arguments")?.map { it.asString } ?: d.acpArguments
        )
    }

    /** Picks the build for this device. Keys follow the ACP Registry ("linux-aarch64", ...). */
    private fun selectDistribution(): BinaryDistribution? {
        for (abi in Build.SUPPORTED_ABIS) {
            val keys = when (abi) {
                "arm64-v8a" -> listOf("android_arm64", "linux-aarch64")
                "x86_64" -> listOf("android_x64", "linux-x86_64")
                else -> emptyList()
            }
            keys.firstNotNullOfOrNull { manifest.distribution[it] }?.let { return it }
        }
        return null
    }

    // ---- status ----------------------------------------------------------------------------

    suspend fun checkInstallationAndStatus() = withContext(Dispatchers.IO) {
        if (runningProcess?.isAlive == true) return@withContext // agent is running; keep state
        // Trust the disk, not the preference flag.
        if (executableFile.isFile) {
            _processState.value = ProcessState.Stopped
        } else {
            prefs.edit().remove(KEY_IS_INSTALLED).remove(KEY_INSTALLED_VERSION).apply()
            _processState.value = ProcessState.Available
        }
    }

    // ---- install ---------------------------------------------------------------------------

    suspend fun installAgent(onProgress: ((Float, String) -> Unit)? = null): Result<Unit> =
        withContext(Dispatchers.IO) {
            val archive = File(context.cacheDir, "opencode-download.tmp")
            fun report(p: Float, msg: String) {
                _processState.value = ProcessState.Downloading(p, msg)
                onProgress?.invoke(p, msg)
            }
            try {
                val dist = selectDistribution() ?: throw IOException(
                    "No OpenCode build for this device (ABIs: ${Build.SUPPORTED_ABIS.joinToString()})"
                )
                if (!dist.url.startsWith("https://")) {
                    throw SecurityException("Download URL must use HTTPS")
                }
                if (!SHA256_REGEX.matches(dist.sha256)) {
                    throw SecurityException("Manifest has no valid SHA-256 for this build; refusing to install")
                }

                report(0.02f, "Downloading OpenCode v${manifest.version}...")
                val actual = download(dist.url, archive) { frac ->
                    report(0.02f + 0.78f * frac, "Downloading OpenCode v${manifest.version}...")
                }

                report(0.82f, "Verifying SHA-256 checksum...")
                if (!actual.equals(dist.sha256, ignoreCase = true)) {
                    throw SecurityException("Checksum mismatch: expected ${dist.sha256}, got $actual")
                }

                report(0.86f, "Extracting...")
                stagingDir.deleteRecursively()
                stagingDir.mkdirs()
                extractTarGz(archive, stagingDir)

                val exe = File(stagingDir, dist.executablePath)
                if (!exe.isFile) throw IOException("Executable not found in package: ${dist.executablePath}")
                exe.setExecutable(true, true)

                report(0.96f, "Finalizing...")
                runtimeDir.deleteRecursively()
                if (!stagingDir.renameTo(runtimeDir)) throw IOException("Could not move runtime into place")

                prefs.edit()
                    .putBoolean(KEY_IS_INSTALLED, true)
                    .putString(KEY_INSTALLED_VERSION, manifest.version)
                    .apply()
                report(1.0f, "Installation complete.")
                _processState.value = ProcessState.Stopped
                Result.success(Unit)
            } catch (e: CancellationException) {
                stagingDir.deleteRecursively()
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to install OpenCode agent", e)
                stagingDir.deleteRecursively()
                _processState.value = ProcessState.Error("Installation failed: ${e.message}", e)
                Result.failure(e)
            } finally {
                archive.delete()
            }
        }

    /** Streams [url] into [dest] and returns the lower-case hex SHA-256 of what was written. */
    private fun download(url: String, dest: File, onProgress: (Float) -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val request = Request.Builder().url(url).get().build()
        downloadClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Download failed: HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Empty response body")
            val total = body.contentLength()
            var read = 0L
            var lastReported = -1f
            body.byteStream().use { input ->
                dest.outputStream().buffered().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val f = (read.toFloat() / total).coerceIn(0f, 1f)
                            if (f - lastReported >= 0.02f) {
                                lastReported = f
                                onProgress(f)
                            }
                        }
                    }
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Extracts a .tar.gz, refusing entries that escape [destDir] and skipping links/devices. */
    private fun extractTarGz(archive: File, destDir: File) {
        val canonicalDest = destDir.canonicalFile
        TarArchiveInputStream(GzipCompressorInputStream(archive.inputStream().buffered())).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                val out = File(canonicalDest, entry.name).canonicalFile
                if (!out.toPath().startsWith(canonicalDest.toPath())) {
                    throw SecurityException("Archive entry escapes target directory: ${entry.name}")
                }
                when {
                    entry.isDirectory -> out.mkdirs()
                    entry.isFile -> {
                        out.parentFile?.mkdirs()
                        out.outputStream().buffered().use { tar.copyTo(it) }
                        if ((entry.mode and 0x49) != 0) out.setExecutable(true, true)
                    }
                    else -> Log.w(TAG, "Skipping non-regular archive entry: ${entry.name}")
                }
                entry = tar.nextEntry
            }
        }
    }

    // ---- run -------------------------------------------------------------------------------

    suspend fun startServer(targetPort: Int = DEFAULT_PORT): Result<Int> = withContext(Dispatchers.IO) {
        (processState.value as? ProcessState.Running)?.let { return@withContext Result.success(it.port) }
        try {
            if (!executableFile.isFile) throw IllegalStateException("OpenCode is not installed")

            _processState.value = ProcessState.Starting("Allocating local port...")
            val port = if (isPortAvailable(targetPort)) targetPort else findFreePort(targetPort + 1)
            prefs.edit().putInt(KEY_ASSIGNED_PORT, port).apply()

            val args = manifest.spawnArguments.map { it.replace("\${PORT}", port.toString()) }
            val builder = ProcessBuilder(listOf(executableFile.absolutePath) + args)
                .directory(runtimeDir)
                .redirectErrorStream(true)
            builder.environment().apply {
                putAll(manifest.environmentVariables)
                put("PORT", port.toString())
            }

            _processState.value = ProcessState.Starting("Starting OpenCode on 127.0.0.1:$port...")
            synchronized(outputTail) { outputTail.clear() }
            val process = builder.start()
            runningProcess = process
            coroutineScope.launch(Dispatchers.IO) {
                runCatching {
                    process.inputStream.bufferedReader().forEachLine { line ->
                        synchronized(outputTail) {
                            outputTail.addLast(line)
                            while (outputTail.size > 40) outputTail.removeFirst()
                        }
                    }
                }
            }

            _processState.value = ProcessState.Starting("Waiting for health check...")
            val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
            var healthy = false
            while (System.currentTimeMillis() < deadline) {
                if (!process.isAlive) {
                    throw IOException("Agent exited with code ${process.exitValue()}.\n${outputText()}")
                }
                if (isServerHealthy(port)) { healthy = true; break }
                delay(400)
            }
            if (!healthy) {
                throw IOException("Agent did not become healthy on port $port within ${START_TIMEOUT_MS / 1000}s.\n${outputText()}")
            }

            _processState.value = ProcessState.Running(port = port)
            startPeriodicHealthWatch(port)
            Result.success(port)
        } catch (e: CancellationException) {
            killProcess()
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start OpenCode server", e)
            killProcess()
            _processState.value = ProcessState.Error("Server start failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Launches the agent for ACP. stdio is the transport, so there is no port, no HTTP and no
     * health endpoint: the caller (AcpAgentClient) performs the initialize handshake on the
     * returned process. The working directory is the project root.
     */
    suspend fun launchAcpAgent(projectRoot: File): Result<Process> = withContext(Dispatchers.IO) {
        try {
            if (!executableFile.isFile) throw IllegalStateException("OpenCode is not installed")
            killProcess()
            val builder = ProcessBuilder(listOf(executableFile.absolutePath) + manifest.acpArguments)
                .directory(projectRoot)
            // Android has no usable $HOME/XDG dirs; OpenCode keeps config + credentials
            // (auth.json) under them, so give it app-private ones.
            val home = File(context.filesDir, "opencode-home").apply { mkdirs() }
            fun dir(rel: String) = File(home, rel).apply { mkdirs() }.absolutePath
            builder.environment().apply {
                putAll(manifest.environmentVariables)
                put("HOME", home.absolutePath)
                put("XDG_CONFIG_HOME", dir(".config"))
                put("XDG_DATA_HOME", dir(".local/share"))
                put("XDG_STATE_HOME", dir(".local/state"))
                put("XDG_CACHE_HOME", dir(".cache"))
                put("TMPDIR", context.cacheDir.absolutePath)
                // Product rule: file edits need approval, no shell for the agent. OpenCode then
                // sends session/request_permission before an edit (handled by AcpAgentClient).
                put("OPENCODE_PERMISSION", """{"edit":"ask","bash":"deny"}""")
            }
            val command = (listOf(executableFile.absolutePath) + manifest.acpArguments).joinToString(" ")
            _processState.value = ProcessState.Starting("Launch: $command")
            val process = builder.start()
            runningProcess = process
            Result.success(process)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch ACP agent", e)
            _processState.value = ProcessState.Error("Agent launch failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /** Progress text shown in the status dialog while the ACP handshake runs. */
    fun reportStarting(message: String) {
        _processState.value = ProcessState.Starting(message)
    }

    /** The ACP handshake succeeded: the agent is usable. (No port in ACP mode.) */
    fun markRunning() {
        _processState.value = ProcessState.Running(port = 0)
    }

    /** Called when the ACP connection ended (process died or was closed). */
    fun markStopped() {
        runningProcess = null
        if (_processState.value is ProcessState.Running) _processState.value = ProcessState.Stopped
    }

    suspend fun checkHealth(port: Int = DEFAULT_PORT): Boolean = withContext(Dispatchers.IO) {
        isServerHealthy(port)
    }

    /** Healthy == a 2xx answer from the manifest's health endpoint (or the OpenAPI doc). */
    private fun isServerHealthy(port: Int): Boolean {
        val endpoints = listOf(manifest.healthCheckEndpoint.ifBlank { "/health" }, "/doc").distinct()
        for (path in endpoints) {
            try {
                val req = Request.Builder().url("http://127.0.0.1:$port$path").get().build()
                httpClient.newCall(req).execute().use { resp -> if (resp.isSuccessful) return true }
            } catch (_: Exception) {
                // try next endpoint
            }
        }
        return false
    }

    private fun startPeriodicHealthWatch(port: Int) {
        healthCheckJob?.cancel()
        healthCheckJob = coroutineScope.launch(Dispatchers.IO) {
            var failures = 0
            while (isActive) {
                delay(15_000L)
                if (isServerHealthy(port)) {
                    failures = 0
                } else if (++failures >= MAX_HEALTH_FAILURES && _processState.value is ProcessState.Running) {
                    Log.w(TAG, "Agent stopped responding on port $port")
                    _processState.value = ProcessState.Error("Agent server stopped responding")
                    break
                }
            }
        }
    }

    suspend fun stopServer(): Unit = withContext(Dispatchers.IO) {
        healthCheckJob?.cancel()
        healthCheckJob = null
        runningProcess?.let { proc ->
            try {
                proc.destroy() // SIGTERM
                var waited = 0
                while (proc.isAlive && waited < 1500) { delay(100); waited += 100 }
                if (proc.isAlive) proc.destroyForcibly() // SIGKILL
            } catch (e: Exception) {
                Log.d(TAG, "Error stopping process: ${e.message}")
            }
        }
        runningProcess = null
        _processState.value = ProcessState.Stopped
    }

    suspend fun uninstallAgent(): Unit = withContext(Dispatchers.IO) {
        stopServer()
        runtimeDir.deleteRecursively()
        stagingDir.deleteRecursively()
        prefs.edit().remove(KEY_IS_INSTALLED).remove(KEY_INSTALLED_VERSION).remove(KEY_ASSIGNED_PORT).apply()
        _processState.value = ProcessState.Available
    }

    // ---- helpers ---------------------------------------------------------------------------

    private fun killProcess() {
        healthCheckJob?.cancel()
        healthCheckJob = null
        runningProcess?.runCatching { destroyForcibly() }
        runningProcess = null
    }

    private fun outputText(): String =
        synchronized(outputTail) { outputTail.joinToString("\n") }.ifBlank { "(no process output)" }

    private fun isPortAvailable(port: Int): Boolean = try {
        ServerSocket(port).use { true }
    } catch (_: Exception) {
        false
    }

    private fun findFreePort(start: Int): Int {
        var p = start
        while (p < 65535) {
            if (isPortAvailable(p)) return p
            p++
        }
        throw IOException("No free local port available")
    }
}
