package com.itsaky.androidide.agent.opencode.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itsaky.androidide.agent.opencode.model.OpenCodeServerConfig

enum class FileModificationMode {
    SAFE_REVIEW,
    DIRECT_AUTO_APPLY,
    AUTO_APPLY_WITH_REVERT
}

enum class ServerLocationType {
    SELF_CONTAINED_LOCAL,
    LOCAL_ANDROID_TERMUX,
    LOCAL_NETWORK_PC,
    REMOTE_CLOUD
}

data class OpenCodePreferences(
    val modificationMode: FileModificationMode = FileModificationMode.SAFE_REVIEW,
    val serverLocation: ServerLocationType = ServerLocationType.SELF_CONTAINED_LOCAL,
    val includeCurrentFile: Boolean = true,
    val includeSelectedCode: Boolean = true,
    val includeBuildErrors: Boolean = true,
    val includeFileTree: Boolean = false,
    val requireAuth: Boolean = false,
    val host: String = "127.0.0.1",
    val port: Int = 4098,
    val token: String = ""
)

@Composable
fun OpenCodeSetupCard(
    currentConfig: OpenCodeServerConfig,
    onSavePreferences: (OpenCodeServerConfig) -> Unit,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var modificationMode by remember {
        mutableStateOf(
            if (currentConfig.autoApplyChanges) FileModificationMode.DIRECT_AUTO_APPLY
            else FileModificationMode.SAFE_REVIEW
        )
    }

    var serverLocation by remember {
        mutableStateOf(
            if (currentConfig.host == "localhost" || currentConfig.host == "127.0.0.1") ServerLocationType.SELF_CONTAINED_LOCAL
            else if (currentConfig.host.startsWith("http") || currentConfig.useTls) ServerLocationType.REMOTE_CLOUD
            else ServerLocationType.LOCAL_NETWORK_PC
        )
    }

    var includeCurrentFile by remember { mutableStateOf(true) }
    var includeSelectedCode by remember { mutableStateOf(true) }
    var includeBuildErrors by remember { mutableStateOf(true) }
    var includeFileTree by remember { mutableStateOf(false) }

    var requireAuth by remember { mutableStateOf(currentConfig.authToken.isNotBlank()) }
    var host by remember { mutableStateOf(currentConfig.host) }
    var port by remember { mutableStateOf(currentConfig.port.toString()) }
    var token by remember { mutableStateOf(currentConfig.authToken) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "OpenCode Agent Setup & Configuration",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Configure how the agent interacts with your projects, modifies files, and connects to your server.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            // Question 1: File Modification Mode
            QuestionHeader("1. How should the agent handle project file modifications?")
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SelectableRadioOption(
                    title = "Option A: Safe Review with Diff (Recommended)",
                    subtitle = "Shows interactive line-by-line diff in chat. Only modifies files when you tap 'Apply'.",
                    selected = modificationMode == FileModificationMode.SAFE_REVIEW,
                    onClick = { modificationMode = FileModificationMode.SAFE_REVIEW }
                )
                SelectableRadioOption(
                    title = "Option B: Direct Auto-Apply",
                    subtitle = "Instantly writes changes to project files and reloads the editor buffer automatically.",
                    selected = modificationMode == FileModificationMode.DIRECT_AUTO_APPLY,
                    onClick = { modificationMode = FileModificationMode.DIRECT_AUTO_APPLY }
                )
                SelectableRadioOption(
                    title = "Option C: Auto-Apply with Revert",
                    subtitle = "Writes files directly to disk while keeping a one-tap 'Undo / Revert' card in chat.",
                    selected = modificationMode == FileModificationMode.AUTO_APPLY_WITH_REVERT,
                    onClick = { modificationMode = FileModificationMode.AUTO_APPLY_WITH_REVERT }
                )
            }

            HorizontalDivider()

            // Question 2: Server Location
            QuestionHeader("2. Where is your OpenCode server hosted?")
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SelectableRadioOption(
                    title = "Self-Contained Local Server (v1.18.31 • 127.0.0.1:4098)",
                    subtitle = "Runs as an isolated process on device without third-party API keys (Recommended).",
                    selected = serverLocation == ServerLocationType.SELF_CONTAINED_LOCAL,
                    onClick = {
                        serverLocation = ServerLocationType.SELF_CONTAINED_LOCAL
                        host = "127.0.0.1"
                        port = "4098"
                    }
                )
                SelectableRadioOption(
                    title = "On-Device Termux (http://127.0.0.1:4098)",
                    subtitle = "Server running directly inside Code On The Go's embedded Termux shell.",
                    selected = serverLocation == ServerLocationType.LOCAL_ANDROID_TERMUX,
                    onClick = {
                        serverLocation = ServerLocationType.LOCAL_ANDROID_TERMUX
                        host = "127.0.0.1"
                        port = "4098"
                    }
                )
                SelectableRadioOption(
                    title = "Local Network PC / Mac (e.g. 192.168.1.X)",
                    subtitle = "Server running on your computer via `opencode serve` on the same Wi-Fi.",
                    selected = serverLocation == ServerLocationType.LOCAL_NETWORK_PC,
                    onClick = {
                        serverLocation = ServerLocationType.LOCAL_NETWORK_PC
                        if (host == "localhost" || host == "127.0.0.1") host = "10.0.2.2"
                    }
                )
                SelectableRadioOption(
                    title = "Remote Cloud Server / VPS",
                    subtitle = "Hosted on an external HTTPS domain or VPS instance.",
                    selected = serverLocation == ServerLocationType.REMOTE_CLOUD,
                    onClick = { serverLocation = ServerLocationType.REMOTE_CLOUD }
                )
            }

            // Host & Port input
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Server Host / IP") },
                    modifier = Modifier.weight(2f),
                    singleLine = true
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("Port") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
            }

            HorizontalDivider()

            // Question 3: Context to include
            QuestionHeader("3. What project context should be automatically sent to OpenCode?")
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SelectableCheckboxOption(
                    title = "Current Active File",
                    subtitle = "Sends the content of the currently open editor tab.",
                    checked = includeCurrentFile,
                    onCheckedChange = { includeCurrentFile = it }
                )
                SelectableCheckboxOption(
                    title = "Selected Code Snippet",
                    subtitle = "Sends currently highlighted text for focused edits or explanations.",
                    checked = includeSelectedCode,
                    onCheckedChange = { includeSelectedCode = it }
                )
                SelectableCheckboxOption(
                    title = "Latest Build / Diagnostic Errors",
                    subtitle = "Sends Gradle compile errors or Logcat failures for automated bug fixing.",
                    checked = includeBuildErrors,
                    onCheckedChange = { includeBuildErrors = it }
                )
                SelectableCheckboxOption(
                    title = "Project Directory Structure",
                    subtitle = "Sends high-level project file tree for multi-file context.",
                    checked = includeFileTree,
                    onCheckedChange = { includeFileTree = it }
                )
            }

            HorizontalDivider()

            // Question 4: Authentication
            QuestionHeader("4. Does your server require authentication?")
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SelectableRadioOption(
                    title = "No Authentication (Trusted Network)",
                    subtitle = "No password or token required.",
                    selected = !requireAuth,
                    onClick = { requireAuth = false }
                )
                SelectableRadioOption(
                    title = "Bearer Token / API Key Required",
                    subtitle = "Authenticate requests with a secure authorization token.",
                    selected = requireAuth,
                    onClick = { requireAuth = true }
                )
            }

            if (requireAuth) {
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Bearer Token / API Key") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(8.dp))

            // Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onDismiss != null) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.padding(end = 8.dp)) {
                        Text("Cancel")
                    }
                }
                Button(
                    onClick = {
                        val parsedPort = port.toIntOrNull() ?: 4096
                        val updatedConfig = currentConfig.copy(
                            host = host.trim(),
                            port = parsedPort,
                            authToken = if (requireAuth) token.trim() else "",
                            autoApplyChanges = modificationMode != FileModificationMode.SAFE_REVIEW
                        )
                        onSavePreferences(updatedConfig)
                    },
                    modifier = Modifier.testTag("submit_setup_button")
                ) {
                    Text("Submit & Connect", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun QuestionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun SelectableRadioOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SelectableCheckboxOption(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (checked) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = checked, onCheckedChange = null)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(text = title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
