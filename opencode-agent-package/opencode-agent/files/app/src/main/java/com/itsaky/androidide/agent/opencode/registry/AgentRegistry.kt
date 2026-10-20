package com.itsaky.androidide.agent.opencode.registry

import com.itsaky.androidide.agent.opencode.model.AgentServerManifest
import com.itsaky.androidide.agent.opencode.model.ProcessState
import com.itsaky.androidide.agent.opencode.model.RunningAgentInstance
import kotlinx.coroutines.flow.StateFlow

/**
 * Registry interface for self-contained, binary-based local agent servers.
 * Maintains real-time state for AVAILABLE, DOWNLOADING, STOPPED, STARTING, and RUNNING instances.
 */
interface AgentRegistry {

    /**
     * Observable list of all agent manifests currently known to the registry.
     */
    val registeredAgents: StateFlow<List<AgentServerManifest>>

    /**
     * Observable map of agentId to its current process/lifecycle state.
     * Tracks AVAILABLE, DOWNLOADING, STOPPED, STARTING, RUNNING, and ERROR.
     */
    val agentStates: StateFlow<Map<String, ProcessState>>

    /**
     * Observable list of actively running agent instances bound to localhost loopback ports.
     */
    val runningInstances: StateFlow<List<RunningAgentInstance>>

    /**
     * Registers a new agent server manifest into the local catalog.
     * @param manifest The self-contained agent server manifest.
     * @return True if registered successfully, false if an agent with this ID already exists.
     */
    fun registerAgent(manifest: AgentServerManifest): Boolean

    /**
     * Unregisters an agent from the catalog. If it is currently running, stops it first.
     * @param agentId The unique agent ID.
     * @return True if removed, false if not found.
     */
    suspend fun unregisterAgent(agentId: String): Boolean

    /**
     * Retrieves the manifest for an agent ID, or null if not registered.
     */
    fun getAgentManifest(agentId: String): AgentServerManifest?

    /**
     * Retrieves the current process/lifecycle state for an agent ID.
     */
    fun getAgentState(agentId: String): ProcessState

    /**
     * Downloads, verifies checksums, and installs an agent into an isolated local runtime sandbox.
     * Transitions state from AVAILABLE -> DOWNLOADING -> STOPPED.
     *
     * @param agentId The target agent ID to install.
     * @param onProgress Optional progress callback (progress: 0.0 - 1.0, statusMessage).
     */
    suspend fun installAgent(
        agentId: String,
        onProgress: ((Float, String) -> Unit)? = null
    ): Result<Unit>

    /**
     * Spawns an installed agent process on localhost and waits for the health check to succeed.
     * Transitions state from STOPPED -> STARTING -> RUNNING.
     *
     * @param agentId The agent to spawn.
     * @param targetPort Optional preferred port (defaults to manifest.defaultPort or next free port).
     * @return Result containing the active RunningAgentInstance with assigned port and PID.
     */
    suspend fun spawnAgent(
        agentId: String,
        targetPort: Int? = null
    ): Result<RunningAgentInstance>

    /**
     * Stops a running agent server instance gracefully (SIGTERM with SIGKILL escalation).
     * Transitions state from RUNNING -> STOPPED.
     *
     * @param agentId The running agent ID to stop.
     */
    suspend fun stopAgent(agentId: String): Result<Unit>

    /**
     * Stops and deletes an installed agent's binaries from the local sandbox.
     * Transitions state to AVAILABLE.
     *
     * @param agentId The agent ID to uninstall.
     */
    suspend fun uninstallAgent(agentId: String): Result<Unit>

    /**
     * Probes loopback ports to discover active agent server instances (e.g. running in Termux or host).
     * Automatically registers and attaches to any detected servers.
     */
    suspend fun discoverActiveInstances(): List<RunningAgentInstance>
}
