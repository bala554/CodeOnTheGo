package com.itsaky.androidide.agent.opencode.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.DialogFragment
import com.itsaky.androidide.agent.opencode.model.ProcessState
import com.itsaky.androidide.agent.opencode.model.ServerConnectionStatus
import com.itsaky.androidide.agent.opencode.viewmodel.OpenCodeChatUiState
import com.itsaky.androidide.agent.opencode.viewmodel.OpenCodeChatViewModel
import com.itsaky.androidide.ui.compose.theme.ManagerTheme
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Status dialog for the OpenCode agent, modelled on the reference app:
 *
 *   OpenCode
 *   Status      Running
 *   Version     v1.18.35
 *   Connection  ACP (stdio)          (or http://127.0.0.1:4098 when the agent exposes a port)
 *   [ CHAT ]
 *   STOP   HIDE
 *
 * While starting, the status line shows the exact launch command, which is what makes
 * "Permission denied" / "not found" failures easy to diagnose on a device.
 */
@Composable
fun OpenCodeAgentStatusContent(
    state: OpenCodeChatUiState,
    onChat: () -> Unit,
    onInstall: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier
) {
    val process = state.processState
    val connected = state.connectionStatus is ServerConnectionStatus.Connected

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = "OpenCode",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(20.dp))

            StatusField(
                label = "Status",
                value = statusLabel(process),
                bold = true,
                valueColor = if (process is ProcessState.Error) MaterialTheme.colorScheme.error else null
            )

            when (process) {
                is ProcessState.Starting -> {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = process.statusMessage,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                is ProcessState.Downloading -> {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = process.statusMessage,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { process.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                is ProcessState.Error -> {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = process.message,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                else -> Unit
            }

            Spacer(Modifier.height(16.dp))
            StatusField("Version", "v${state.manifest.version}")
            Spacer(Modifier.height(16.dp))
            StatusField("Connection", connectionLabel(process, state))
            Spacer(Modifier.height(24.dp))

            when (process) {
                is ProcessState.Running ->
                    PrimaryAction("CHAT", enabled = connected, onClick = onChat)
                is ProcessState.Stopped ->
                    PrimaryAction("START", onClick = onStart)
                is ProcessState.Available ->
                    PrimaryAction("INSTALL", onClick = onInstall)
                is ProcessState.Error ->
                    PrimaryAction("RETRY", onClick = onStart)
                is ProcessState.Starting, is ProcessState.Downloading ->
                    PrimaryAction(
                        if (process is ProcessState.Downloading) "INSTALLING..." else "STARTING...",
                        enabled = false,
                        onClick = {}
                    )
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                TextButton(
                    onClick = onStop,
                    enabled = process is ProcessState.Running || process is ProcessState.Starting
                ) { Text("STOP", letterSpacing = 1.5.sp) }
                TextButton(onClick = onHide) { Text("HIDE", letterSpacing = 1.5.sp) }
            }
        }
    }
}

@Composable
private fun StatusField(
    label: String,
    value: String,
    bold: Boolean = false,
    valueColor: ComposeColor? = null
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
            color = valueColor ?: MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun PrimaryAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(14.dp)
    ) {
        Text(text, fontWeight = FontWeight.Medium, letterSpacing = 1.5.sp)
    }
}

private fun statusLabel(p: ProcessState): String = when (p) {
    is ProcessState.Available -> "Not installed"
    is ProcessState.Downloading -> "Installing ${(p.progress * 100).toInt()}%"
    is ProcessState.Stopped -> "Stopped"
    is ProcessState.Starting -> "Starting"
    is ProcessState.Running -> "Running"
    is ProcessState.Error -> "Error"
}

private fun connectionLabel(p: ProcessState, state: OpenCodeChatUiState): String = when {
    // ACP mode runs without a port; an HTTP-style agent would report a real one.
    p is ProcessState.Running && p.port > 0 -> "http://${p.host}:${p.port}"
    p is ProcessState.Running -> "ACP (stdio)"
    state.connectionStatus is ServerConnectionStatus.Connected -> "ACP (stdio)"
    else -> "-"
}

/**
 * Hosts [OpenCodeAgentStatusContent]. CHAT closes the dialog and opens the chat sheet,
 * forwarding the editor context given to [newInstance].
 */
class OpenCodeAgentStatusDialogFragment : DialogFragment() {

    companion object {
        const val TAG = "OpenCodeAgentStatusDialog"

        fun newInstance(
            activeFilePath: String? = null,
            activeFileName: String? = null,
            selectedText: String? = null
        ) = OpenCodeAgentStatusDialogFragment().apply {
            arguments = Bundle().apply {
                putString("active_file_path", activeFilePath)
                putString("active_file_name", activeFileName)
                putString("selected_text", selectedText)
            }
        }
    }

    private val viewModel: OpenCodeChatViewModel by viewModel()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = ComposeView(requireContext()).apply {
        setContent {
            ManagerTheme {
                val state by viewModel.uiState.collectAsState()
                OpenCodeAgentStatusContent(
                    state = state,
                    onChat = { openChat() },
                    onInstall = { viewModel.installAgent() },
                    onStart = { viewModel.startLocalServer() },
                    onStop = { viewModel.stopLocalServer() },
                    onHide = { dismiss() }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun openChat() {
        val fm = parentFragmentManager
        val args = arguments
        dismiss()
        if (fm.findFragmentByTag(OpenCodeChatBottomSheet.TAG) == null) {
            OpenCodeChatBottomSheet.newInstance(
                activeFilePath = args?.getString("active_file_path"),
                activeFileName = args?.getString("active_file_name"),
                selectedText = args?.getString("selected_text")
            ).show(fm, OpenCodeChatBottomSheet.TAG)
        }
    }
}
