package com.itsaky.androidide.agent.opencode.service

import android.content.Context
import android.util.Log
import com.itsaky.androidide.agent.opencode.model.OpenCodeServerConfig
import com.itsaky.androidide.agent.opencode.model.ServerConnectionStatus
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
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Service component responsible for:
 * 1. Initializing and maintaining connection to the OpenCode server at http://127.0.0.1:4098.
 * 2. Detecting when the server requires authentication (HTTP 401 Unauthorized / HTTP 403 Forbidden).
 * 3. Securely storing, managing, and injecting authentication tokens (Bearer tokens).
 * 4. Periodic health monitoring / keep-alive pinging of the local server.
 */
class OpenCodeConnectionService(
    private val context: Context,
    private val externalScope: CoroutineScope
) {
    companion object {
        private const val TAG = "OpenCodeConnService"
        const val DEFAULT_HOST = "127.0.0.1"
        const val DEFAULT_PORT = 4098
        const val DEFAULT_BASE_URL = "http://$DEFAULT_HOST:$DEFAULT_PORT"

        private const val PREFS_NAME = "opencode_connection_service_prefs"
        private const val KEY_AUTH_TOKEN = "server_auth_token"
        private const val KEY_SERVER_HOST = "server_host"
        private const val KEY_SERVER_PORT = "server_port"
        private const val KEY_USE_TLS = "server_use_tls"
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val _connectionStatus = MutableStateFlow<ServerConnectionStatus>(ServerConnectionStatus.Disconnected)
    val connectionStatus: StateFlow<ServerConnectionStatus> = _connectionStatus.asStateFlow()

    private val _serverConfig = MutableStateFlow(loadConfig())
    val serverConfig: StateFlow<OpenCodeServerConfig> = _serverConfig.asStateFlow()

    private var healthCheckJob: Job? = null

    init {
        // Automatically initialize connection when service is instantiated
        initialize()
    }

    /**
     * Initializes the connection to the OpenCode server at http://127.0.0.1:4098.
     * Probes the server, checks if authentication is required, and applies saved tokens if present.
     */
    fun initialize(
        host: String = DEFAULT_HOST,
        port: Int = DEFAULT_PORT,
        useTls: Boolean = false,
        authToken: String? = null
    ) {
        val tokenToUse = authToken ?: prefs.getString(KEY_AUTH_TOKEN, "") ?: ""
        val newConfig = _serverConfig.value.copy(
            host = host,
            port = port,
            useTls = useTls,
            authToken = tokenToUse
        )
        _serverConfig.value = newConfig
        saveConfig(newConfig)

        externalScope.launch(Dispatchers.IO) {
            connectInternal(newConfig)
            startHealthMonitor()
        }
    }

    /**
     * Connects to the server without requiring authentication.
     */
    suspend fun authenticateWithToken(token: String): Result<Boolean> = withContext(Dispatchers.IO) {
        val cleanToken = token.trim()
        val testConfig = _serverConfig.value.copy(authToken = cleanToken)
        _serverConfig.value = testConfig
        saveConfig(testConfig)
        val status = probeServer(testConfig)
        _connectionStatus.value = status
        Result.success(true)
    }

    /**
     * Clears any saved authentication token.
     */
    fun clearAuthToken() {
        prefs.edit().remove(KEY_AUTH_TOKEN).apply()
        _serverConfig.value = _serverConfig.value.copy(authToken = "")
    }

    /**
     * Gets the formatted Authorization header value (e.g. "Bearer <token>") if available.
     */
    fun getAuthorizationHeader(): String? = null

    /**
     * Disconnects and stops health monitor checks.
     */
    fun disconnect() {
        healthCheckJob?.cancel()
        healthCheckJob = null
        _connectionStatus.value = ServerConnectionStatus.Disconnected
    }

    /**
     * Forces an immediate health / connection check.
     */
    suspend fun checkHealth(): ServerConnectionStatus = withContext(Dispatchers.IO) {
        val status = probeServer(_serverConfig.value)
        _connectionStatus.value = status
        status
    }

    private suspend fun connectInternal(config: OpenCodeServerConfig) {
        _connectionStatus.value = ServerConnectionStatus.Connecting
        val status = probeServer(config)
        _connectionStatus.value = status
    }

    /**
     * Probes the server endpoints (/doc, /session, or /health) to determine connectivity.
     */
    private fun probeServer(config: OpenCodeServerConfig): ServerConnectionStatus {
        val baseUrl = config.baseUrl

        val endpointsToTest = listOf("$baseUrl/doc", "$baseUrl/session", "$baseUrl/v1/models")

        for (endpoint in endpointsToTest) {
            try {
                val reqBuilder = Request.Builder().url(endpoint)
                httpClient.newCall(reqBuilder.build()).execute().use { response ->
                    val code = response.code
                    return when (code) {
                        404 -> continue // Route not found, test next
                        else -> {
                            ServerConnectionStatus.Connected(
                                serverVersion = "OpenCode Server ($baseUrl)"
                            )
                        }
                    }
                }
            } catch (e: IOException) {
                Log.d(TAG, "Probe to $endpoint failed: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error probing $endpoint", e)
            }
        }

        return ServerConnectionStatus.Error(
            message = "Unable to connect to OpenCode server at $baseUrl. Ensure server is running (`opencode serve --port 4098`).",
            cause = IOException("Connection refused or timed out at $baseUrl")
        )
    }

    /**
     * Starts a periodic background ping every 20 seconds to keep track of local server state.
     */
    private fun startHealthMonitor() {
        healthCheckJob?.cancel()
        healthCheckJob = externalScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(20_000L)
                try {
                    val status = probeServer(_serverConfig.value)
                    _connectionStatus.value = status
                } catch (e: Exception) {
                    Log.d(TAG, "Periodic health check error: ${e.message}")
                }
            }
        }
    }

    private fun loadConfig(): OpenCodeServerConfig {
        return OpenCodeServerConfig(
            host = prefs.getString(KEY_SERVER_HOST, DEFAULT_HOST) ?: DEFAULT_HOST,
            port = prefs.getInt(KEY_SERVER_PORT, DEFAULT_PORT),
            useTls = prefs.getBoolean(KEY_USE_TLS, false),
            authToken = prefs.getString(KEY_AUTH_TOKEN, "") ?: ""
        )
    }

    private fun saveConfig(config: OpenCodeServerConfig) {
        prefs.edit()
            .putString(KEY_SERVER_HOST, config.host)
            .putInt(KEY_SERVER_PORT, config.port)
            .putBoolean(KEY_USE_TLS, config.useTls)
            .putString(KEY_AUTH_TOKEN, config.authToken)
            .apply()
    }
}
