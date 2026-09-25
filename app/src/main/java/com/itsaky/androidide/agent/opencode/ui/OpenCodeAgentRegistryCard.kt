package com.itsaky.androidide.agent.opencode.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itsaky.androidide.agent.opencode.model.AgentCapability
import com.itsaky.androidide.agent.opencode.model.AgentServerManifest
import com.itsaky.androidide.agent.opencode.model.ProcessState
import com.itsaky.androidide.agent.opencode.model.ToolApprovalRequest
import com.itsaky.androidide.agent.opencode.model.ToolApprovalStatus

private val BrandCyan = Color(0xFF00BCD4)
private val BrandDarkCyan = Color(0xFF0097A7)
private val SuccessGreen = Color(0xFF2E7D32)

/**
 * Registry & Lifecycle Management Card for the self-contained local OpenCode server (v1.18.31).
 * Displays manifest details, zero-config status, and interactive controls to install, spawn, and stop.
 */
@Composable
fun OpenCodeAgentRegistryCard(
    manifest: AgentServerManifest,
    processState: ProcessState,
    onInstallAgent: () -> Unit,
    onStartServer: () -> Unit,
    onStopServer: () -> Unit,
    onConfigureServer: () -> Unit,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var isExpandedDetails by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        border = BorderStroke(
            width = 1.dp,
            color = when (processState) {
                is ProcessState.Running -> SuccessGreen.copy(alpha = 0.6f)
                is ProcessState.Downloading, is ProcessState.Starting -> Color(0xFFFFA000).copy(alpha = 0.6f)
                else -> Color(0xFFE0E0E0)
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header: Title, Version Badge & Status Indicator
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(BrandCyan.copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("⚡", fontSize = 18.sp)
                }

                Spacer(Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = manifest.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = Color.Black
                        )
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFFECEFF1)
                        ) {
                            Text(
                                text = "v${manifest.version}",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF37474F),
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }

                    Text(
                        text = "Self-Contained Local Server • No Cloud API Keys",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }

                // Process Status Badge
                ProcessStatusBadge(processState)

                if (onDismiss != null && processState is ProcessState.Running) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = Color.Gray,
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { onDismiss() }
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Body based on ProcessState
            when (processState) {
                is ProcessState.Available -> {
                    Text(
                        text = "The OpenCode AI agent runs as an isolated local server on http://127.0.0.1:4098. No cloud subscriptions or API keys are required.",
                        fontSize = 12.sp,
                        color = Color(0xFF424242)
                    )

                    Spacer(Modifier.height(8.dp))
                    CapabilitiesRow(manifest.capabilities)

                    Spacer(Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onInstallAgent,
                            colors = ButtonDefaults.buttonColors(containerColor = BrandDarkCyan),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("install_opencode_agent_button")
                        ) {
                            Text("Install OpenCode Agent (v${manifest.version})", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = onConfigureServer,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Configure Host", fontSize = 12.sp)
                        }
                    }
                }

                is ProcessState.Downloading -> {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = processState.statusMessage,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFE65100)
                            )
                            Text(
                                text = "${(processState.progress * 100).toInt()}%",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE65100)
                            )
                        }
                        LinearProgressIndicator(
                            progress = { processState.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = Color(0xFFFFA000),
                            trackColor = Color(0xFFFFECB3)
                        )
                    }
                }

                is ProcessState.Stopped -> {
                    Text(
                        text = "OpenCode v${manifest.version} is installed locally in your runtime sandbox and ready to start.",
                        fontSize = 12.sp,
                        color = Color(0xFF424242)
                    )

                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onStartServer,
                            colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("start_local_server_button")
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Start Local Server (Port 4098)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = onConfigureServer,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Settings", fontSize = 12.sp)
                        }
                    }
                }

                is ProcessState.Starting -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFFFFA000)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = processState.statusMessage,
                            fontSize = 12.sp,
                            color = Color(0xFF424242)
                        )
                    }
                }

                is ProcessState.Running -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("●", color = SuccessGreen, fontSize = 12.sp)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Active on http://127.0.0.1:${processState.port} • ACP Ready",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = SuccessGreen
                            )
                        }

                        Row {
                            TextButton(
                                onClick = onConfigureServer,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)
                            ) {
                                Text("Options", fontSize = 11.sp, color = Color.Gray)
                            }
                            TextButton(
                                onClick = onStopServer,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)
                            ) {
                                Text("Stop Server", fontSize = 11.sp, color = Color.Red)
                            }
                        }
                    }
                }

                is ProcessState.Error -> {
                    Text(
                        text = processState.message,
                        fontSize = 12.sp,
                        color = Color.Red
                    )

                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onStartServer,
                            colors = ButtonDefaults.buttonColors(containerColor = BrandDarkCyan),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Retry", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = onInstallAgent,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Reinstall Agent", fontSize = 12.sp)
                        }
                    }
                }
            }

            // Expandable Manifest Inspection Accordion
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpandedDetails = !isExpandedDetails }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (isExpandedDetails) "Hide server manifest specifications" else "View server manifest & ACP specifications",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }

            AnimatedVisibility(visible = isExpandedDetails) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .background(Color(0xFFF9F9F9), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                ) {
                    ManifestItem("Agent ID", manifest.id)
                    ManifestItem("Architecture", "Self-contained ${manifest.serverType.name.lowercase()} process")
                    ManifestItem("Protocol", "Agent Communication Protocol (ACP) over Localhost")
                    ManifestItem("Loopback Port", "${manifest.defaultPort} (dynamic allocation allowed)")
                    ManifestItem("Health Endpoint", manifest.healthCheckEndpoint)
                    ManifestItem("Zero-Config", "Automatic process discovery & health verification")
                }
            }
        }
    }
}

@Composable
private fun ProcessStatusBadge(state: ProcessState) {
    val (label, bg, fg) = when (state) {
        is ProcessState.Available -> Triple("AVAILABLE", Color(0xFFE3F2FD), Color(0xFF1976D2))
        is ProcessState.Downloading -> Triple("DOWNLOADING", Color(0xFFFFF3E0), Color(0xFFE65100))
        is ProcessState.Stopped -> Triple("STOPPED", Color(0xFFECEFF1), Color(0xFF546E7A))
        is ProcessState.Starting -> Triple("STARTING", Color(0xFFFFF8E1), Color(0xFFF57F17))
        is ProcessState.Running -> Triple("RUNNING", Color(0xFFE8F5E9), SuccessGreen)
        is ProcessState.Error -> Triple("ERROR", Color(0xFFFFEBEE), Color(0xFFC62828))
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = bg
    ) {
        Text(
            text = label,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun CapabilitiesRow(capabilities: List<AgentCapability>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        capabilities.forEach { cap ->
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = Color(0xFFF5F5F5),
                border = BorderStroke(0.5.dp, Color(0xFFE0E0E0))
            ) {
                Text(
                    text = cap.name.lowercase(),
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFF424242),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun ManifestItem(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontSize = 11.sp, color = Color.Gray)
        Text(text = value, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color.DarkGray)
    }
}

/**
 * Human-in-the-Loop Tool Approval Card for Sandbox Security.
 * Renders when OpenCode server requests permission for file mutations or terminal executions.
 */
@Composable
fun HumanInTheLoopToolApprovalCard(
    approval: ToolApprovalRequest,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFDE7)),
        border = BorderStroke(1.dp, Color(0xFFFBC02D)),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔒", fontSize = 14.sp)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "Tool Execution Approval Required",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = Color(0xFFF57F17)
                )
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = if (approval.reason.isNotBlank()) approval.reason
                else "OpenCode agent requests permission to execute an isolated operation.",
                fontSize = 12.sp,
                color = Color.Black
            )

            Spacer(Modifier.height(6.dp))

            Surface(
                color = Color.White,
                shape = RoundedCornerShape(6.dp),
                border = BorderStroke(0.5.dp, Color(0xFFE0E0E0)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        text = "Tool: ${approval.toolName}",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF263238)
                    )
                    if (approval.parameters.targetFile != null) {
                        Text(
                            text = "Target: ${approval.parameters.targetFile}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF37474F)
                        )
                    }
                    if (approval.parameters.command != null) {
                        Text(
                            text = "Command: ${approval.parameters.command}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF37474F)
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            when (approval.status) {
                ToolApprovalStatus.PENDING -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = onReject,
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("Reject", fontSize = 11.sp, color = Color.Red)
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = onApprove,
                            colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Approve & Execute", fontSize = 11.sp, color = Color.White)
                        }
                    }
                }
                ToolApprovalStatus.APPROVED -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Approved and executed by user.", fontSize = 11.sp, color = SuccessGreen, fontWeight = FontWeight.Medium)
                    }
                }
                ToolApprovalStatus.REJECTED -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Close, contentDescription = null, tint = Color.Red, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Rejected by user.", fontSize = 11.sp, color = Color.Red, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}
