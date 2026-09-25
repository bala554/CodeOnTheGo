package com.itsaky.androidide.agent.opencode.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import com.itsaky.androidide.agent.opencode.model.ProcessState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.itsaky.androidide.agent.opencode.model.DiffLine
import com.itsaky.androidide.agent.opencode.model.DiffType
import com.itsaky.androidide.agent.opencode.model.MessagePart
import com.itsaky.androidide.agent.opencode.model.MessageRole
import com.itsaky.androidide.agent.opencode.model.OpenCodeMessage
import com.itsaky.androidide.agent.opencode.model.PatchStatus
import com.itsaky.androidide.agent.opencode.model.QuestionOption
import com.itsaky.androidide.agent.opencode.viewmodel.OpenCodeChatViewModel

private val BrandCyan = Color(0xFF0091EA)
private val DarkCardBorder = Color(0xFFE0E0E0)
private val SubmitBlack = Color(0xFF1E1E1E)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenCodeChatScreen(
    viewModel: OpenCodeChatViewModel,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    var inputPrompt by remember { mutableStateOf("") }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Session, 1 = Changes
    var activeMode by remember { mutableStateOf("Plan") } // "Build" vs "Plan"
    var showModeDropdown by remember { mutableStateOf(false) }
    var showModelDropdown by remember { mutableStateOf(false) }
    val selectedModel = uiState.serverConfig.activeModel

    val listState = rememberLazyListState()

    LaunchedEffect(uiState.messages.size, uiState.messages.lastOrNull()?.parts?.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { onClose?.invoke() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                title = {
                    Text(
                        text = "OpenCode",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 19.sp
                    )
                },
                actions = {
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "Menu",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BrandCyan
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.White)
        ) {
            // Icon Tabs Sub-Bar (matching screenshot)
            MiniTabsBar(
                onNewTab = { viewModel.startNewSession() }
            )

            // Segmented Switcher: [ Session ] | [ Changes ]
            SegmentedSessionChangesBar(
                selectedTab = selectedTab,
                onTabSelected = { selectedTab = it }
            )

            // Session Title Header
            SessionTitleHeader(
                sessionTitle = uiState.currentSession?.title ?: "Audio transcriber feature suggestio...",
                serverUrl = uiState.serverConfig.baseUrl,
                connectionStatus = uiState.connectionStatus,
                processState = uiState.processState,
                onMenuClick = { showSettingsDialog = true }
            )

            HorizontalDivider(color = Color(0xFFF0F0F0))

            // Main Content Area
            if (selectedTab == 0) {
                // SESSION TAB: Messages, Agent Registry & Question Cards
                Box(modifier = Modifier.weight(1f)) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        // OpenCode Self-Contained Local Agent Registry & Installer Card
                        if (uiState.processState !is ProcessState.Running) {
                            OpenCodeAgentRegistryCard(
                                manifest = uiState.manifest,
                                processState = uiState.processState,
                                onInstallAgent = { viewModel.installAgent() },
                                onStartServer = { viewModel.startLocalServer() },
                                onStopServer = { viewModel.stopLocalServer() },
                                onConfigureServer = { showSettingsDialog = true },
                                modifier = Modifier.padding(12.dp)
                            )
                        }

                        if (uiState.messages.isEmpty()) {
                            if (uiState.processState is ProcessState.Running) {
                                // Default question card if no messages yet
                                DefaultInitialQuestionCard(
                                    onSubmitAnswer = { answer ->
                                        viewModel.sendMessage(answer)
                                    }
                                )
                            }
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                items(uiState.messages, key = { it.id }) { message ->
                                    ChatMessageItem(
                                        message = message,
                                        onApplyPatch = { patch -> viewModel.applyPatch(message.id, patch) },
                                        onRejectPatch = { patch -> viewModel.rejectPatch(message.id, patch) },
                                        onSubmitQuestion = { answer -> viewModel.sendMessage(answer) },
                                        onEditMessage = { msg ->
                                            val prompt = viewModel.rollbackToMessage(msg.id)
                                            if (prompt != null) {
                                                inputPrompt = prompt
                                            }
                                        },
                                        onToolApproval = { msgId, callId, approved ->
                                            viewModel.respondToToolApproval(msgId, callId, approved)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Rolled back messages chip (if any, matching screenshot 2)
                if (uiState.rolledBackMessages.isNotEmpty()) {
                    RolledBackMessagesBanner(
                        rolledBackMessages = uiState.rolledBackMessages,
                        onRestore = {
                            viewModel.restoreRolledBackMessages()
                            inputPrompt = ""
                        },
                        onDismiss = { viewModel.clearRolledBackMessages() }
                    )
                }

                // Bottom Chat Input Card (matching image 2)
                OpenCodeInputCard(
                    text = inputPrompt,
                    onTextChanged = { inputPrompt = it },
                    isEditing = uiState.editingMessageId != null,
                    onCancelEditing = {
                        viewModel.cancelEditing()
                        inputPrompt = ""
                    },
                    activeMode = activeMode,
                    showModeDropdown = showModeDropdown,
                    onToggleModeDropdown = { showModeDropdown = it },
                    onSelectMode = {
                        activeMode = it
                        showModeDropdown = false
                        viewModel.updateServerConfig(uiState.serverConfig.copy(activeAgent = it.lowercase()))
                    },
                    selectedModel = selectedModel,
                    showModelDropdown = showModelDropdown,
                    onToggleModelDropdown = { showModelDropdown = it },
                    onSelectModel = { modelName ->
                        showModelDropdown = false
                        viewModel.updateServerConfig(uiState.serverConfig.copy(activeModel = modelName))
                    },
                    isGenerating = uiState.isGenerating,
                    onSend = {
                        val p = inputPrompt.trim()
                        if (p.isNotEmpty()) {
                            viewModel.sendMessage(p)
                            inputPrompt = ""
                        }
                    },
                    onCancel = { viewModel.cancelGeneration() }
                )
            } else {
                // CHANGES TAB: Diff & file changes review
                ChangesReviewPane(
                    messages = uiState.messages,
                    onApplyPatch = { msgId, patch -> viewModel.applyPatch(msgId, patch) },
                    onRejectPatch = { msgId, patch -> viewModel.rejectPatch(msgId, patch) }
                )
            }
        }
    }

    if (showSettingsDialog) {
        Dialog(onDismissRequest = { showSettingsDialog = false }) {
            OpenCodeSetupCard(
                currentConfig = uiState.serverConfig,
                onSavePreferences = { newConfig ->
                    viewModel.updateServerConfig(newConfig)
                    showSettingsDialog = false
                },
                onDismiss = { showSettingsDialog = false }
            )
        }
    }
}

/**
 * Top mini tab row with icons matching screenshot 1 & 2:
 * [⊞] [A] [A] [A] [✕ •] [+]
 */
@Composable
private fun MiniTabsBar(onNewTab: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFAFAFA))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("⊞", fontSize = 16.sp, color = Color.Gray, modifier = Modifier.padding(horizontal = 4.dp))
        Text("A", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.DarkGray)
        Text("A", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.DarkGray)
        Text("A", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.DarkGray)

        // Active tab pill with close icon and blue indicator
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFFE0E0E0),
            modifier = Modifier.height(28.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 10.dp)
            ) {
                Text("✕", fontSize = 11.sp, color = Color.Gray)
                Spacer(Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(BrandCyan)
                )
            }
        }

        Spacer(Modifier.weight(1f))

        IconButton(onClick = onNewTab, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.Add, contentDescription = "New Tab", tint = Color.Gray, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * Segmented switcher: [ Session ] | [ Changes ]
 */
@Composable
private fun SegmentedSessionChangesBar(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .background(if (selectedTab == 0) Color.White else Color(0xFFF5F5F5))
                .clickable { onTabSelected(0) }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Session",
                fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal,
                fontSize = 14.sp,
                color = if (selectedTab == 0) Color.Black else Color.Gray
            )
        }

        Box(
            modifier = Modifier
                .width(1.dp)
                .height(40.dp)
                .background(Color(0xFFE0E0E0))
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .background(if (selectedTab == 1) Color.White else Color(0xFFF5F5F5))
                .clickable { onTabSelected(1) }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Changes",
                fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal,
                fontSize = 14.sp,
                color = if (selectedTab == 1) Color.Black else Color.Gray
            )
        }
    }
}

/**
 * Session title header with status indicator and options menu:
 * "Audio transcriber feature suggestio...  ○  ···"
 */
@Composable
private fun SessionTitleHeader(
    sessionTitle: String,
    serverUrl: String,
    connectionStatus: com.itsaky.androidide.agent.opencode.model.ServerConnectionStatus,
    processState: com.itsaky.androidide.agent.opencode.model.ProcessState = com.itsaky.androidide.agent.opencode.model.ProcessState.Available,
    onMenuClick: () -> Unit
) {
    val statusColor = when (processState) {
        is com.itsaky.androidide.agent.opencode.model.ProcessState.Running -> Color(0xFF4CAF50)
        is com.itsaky.androidide.agent.opencode.model.ProcessState.Starting,
        is com.itsaky.androidide.agent.opencode.model.ProcessState.Downloading -> Color(0xFFFFA000)
        is com.itsaky.androidide.agent.opencode.model.ProcessState.Error -> Color(0xFFE53935)
        else -> when (connectionStatus) {
            is com.itsaky.androidide.agent.opencode.model.ServerConnectionStatus.Connected -> Color(0xFF4CAF50)
            is com.itsaky.androidide.agent.opencode.model.ServerConnectionStatus.Connecting -> Color(0xFFFFA000)
            else -> Color.Gray
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = sessionTitle,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = when (processState) {
                    is com.itsaky.androidide.agent.opencode.model.ProcessState.Running -> "OpenCode v1.18.31 • Localhost :${processState.port} • ACP Ready"
                    is com.itsaky.androidide.agent.opencode.model.ProcessState.Starting -> "Starting OpenCode v1.18.31..."
                    is com.itsaky.androidide.agent.opencode.model.ProcessState.Downloading -> "Downloading & verifying OpenCode v1.18.31..."
                    is com.itsaky.androidide.agent.opencode.model.ProcessState.Stopped -> "OpenCode v1.18.31 (Stopped)"
                    is com.itsaky.androidide.agent.opencode.model.ProcessState.Available -> "OpenCode v1.18.31 (Install Required)"
                    else -> when (connectionStatus) {
                        is com.itsaky.androidide.agent.opencode.model.ServerConnectionStatus.Connected -> "Connected to $serverUrl"
                        is com.itsaky.androidide.agent.opencode.model.ServerConnectionStatus.Connecting -> "Connecting to $serverUrl..."
                        else -> "Disconnected from $serverUrl"
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
                fontSize = 10.sp
            )
        }
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(statusColor, CircleShape)
                .border(1.5.dp, Color.Gray, CircleShape)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "•••",
            color = Color.Gray,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable { onMenuClick() }
        )
    }
}

/**
 * Interactive Question Card component supporting:
 * - "X of Y questions ⌄"
 * - Question title & subtitle
 * - Radio options in outlined cards
 * - Next button to advance to the next question when user selects an answer
 * - Final Submit ("cement") button appearing on the last question
 */
@Composable
fun InteractiveQuestionCard(
    question: MessagePart.QuestionCard,
    onSubmit: (String) -> Unit,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val allQuestions = remember(question) { question.getAllQuestions() }
    if (allQuestions.isEmpty()) return

    var currentQuestionIndex by remember(question.id) { mutableStateOf(0) }
    val selectedAnswers = remember(question.id) {
        mutableStateMapOf<String, String>().apply {
            allQuestions.forEach { q ->
                q.selectedOptionId?.let { put(q.id, it) }
            }
        }
    }
    val customAnswers = remember(question.id) {
        mutableStateMapOf<String, String>().apply {
            allQuestions.forEach { q ->
                if (q.customAnswer.isNotEmpty()) put(q.id, q.customAnswer)
            }
        }
    }

    var isExpanded by remember { mutableStateOf(true) }

    val currentQ = allQuestions.getOrElse(currentQuestionIndex) { allQuestions.first() }
    val selectedOptionId = selectedAnswers[currentQ.id]
    val customAnswerText = customAnswers[currentQ.id] ?: ""
    val totalCount = allQuestions.size
    val isLastQuestion = currentQuestionIndex == totalCount - 1

    Card(
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, DarkCardBorder),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: "1 of 3 questions ⌄"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${currentQuestionIndex + 1} of $totalCount questions",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.Black
                )
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier.size(20.dp)
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    Text(
                        text = currentQ.title,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )

                    Text(
                        text = currentQ.subtitle,
                        fontSize = 13.sp,
                        color = Color.Gray,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                    )

                    // Radio Option Cards
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        currentQ.options.forEach { option ->
                            QuestionOptionRowCard(
                                option = option,
                                isSelected = selectedOptionId == option.id,
                                onSelect = {
                                    selectedAnswers[currentQ.id] = option.id
                                },
                                customText = if (option.isCustomInput) customAnswerText else null,
                                onCustomTextChanged = { newText ->
                                    customAnswers[currentQ.id] = newText
                                    selectedAnswers[currentQ.id] = option.id
                                }
                            )
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Bottom action row: "Dismiss / Previous" on left, "Next / Submit" on right
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (currentQuestionIndex > 0) {
                            Text(
                                text = "← Previous",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.Black,
                                modifier = Modifier
                                    .clickable { currentQuestionIndex-- }
                                    .padding(vertical = 8.dp)
                            )
                        } else {
                            Text(
                                text = "Dismiss",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.Black,
                                modifier = Modifier
                                    .clickable { onDismiss?.invoke() }
                                    .padding(vertical = 8.dp)
                            )
                        }

                        if (!isLastQuestion) {
                            // "Next" button appears when user can advance to next question
                            Button(
                                onClick = {
                                    if (currentQuestionIndex < totalCount - 1) {
                                        currentQuestionIndex++
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = SubmitBlack),
                                modifier = Modifier.height(38.dp)
                            ) {
                                Text("Next →", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        } else {
                            // "Submit" button appears after user advances through questions to the last question
                            Button(
                                onClick = {
                                    val summary = if (allQuestions.size == 1) {
                                        val selOpt = currentQ.options.find { it.id == selectedOptionId }
                                        if (selOpt?.isCustomInput == true) {
                                            customAnswerText.ifBlank { "Custom input" }
                                        } else {
                                            selOpt?.title ?: ""
                                        }
                                    } else {
                                        allQuestions.mapIndexed { idx, q ->
                                            val selId = selectedAnswers[q.id]
                                            val selOpt = q.options.find { it.id == selId }
                                            val ansText = if (selOpt?.isCustomInput == true) {
                                                (customAnswers[q.id] ?: "").ifBlank { "Custom answer" }
                                            } else {
                                                selOpt?.title ?: "Default"
                                            }
                                            "${idx + 1}. ${q.title}: $ansText"
                                        }.joinToString("\n")
                                    }
                                    onSubmit(summary)
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = SubmitBlack),
                                modifier = Modifier.height(38.dp)
                            ) {
                                Text("Submit", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QuestionOptionRowCard(
    option: QuestionOption,
    isSelected: Boolean,
    onSelect: () -> Unit,
    customText: String?,
    onCustomTextChanged: ((String) -> Unit)?
) {
    Card(
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (isSelected) Color(0xFFBDBDBD) else DarkCardBorder),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = isSelected, onClick = onSelect, role = Role.RadioButton)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                RadioButton(
                    selected = isSelected,
                    onClick = onSelect,
                    colors = RadioButtonDefaults.colors(
                        selectedColor = Color.Black,
                        unselectedColor = Color(0xFFBDBDBD)
                    ),
                    modifier = Modifier.size(24.dp)
                )

                Spacer(Modifier.width(10.dp))

                Column {
                    Text(
                        text = option.title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.Black
                    )

                    if (!option.description.isNullOrBlank()) {
                        Text(
                            text = option.description,
                            fontSize = 12.sp,
                            color = Color.Gray,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    if (option.isCustomInput && isSelected) {
                        OutlinedTextField(
                            value = customText ?: "",
                            onValueChange = { onCustomTextChanged?.invoke(it) },
                            placeholder = { Text("Type your answer...", fontSize = 12.sp, color = Color.Gray) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = BrandCyan,
                                unfocusedBorderColor = Color(0xFFE0E0E0)
                            )
                        )
                    }
                }
            }
        }
    }
}

/**
 * Default initial question shown when opening the session (matches screenshot 1).
 */
@Composable
private fun DefaultInitialQuestionCard(onSubmitAnswer: (String) -> Unit) {
    val sampleQuestions = listOf(
        com.itsaky.androidide.agent.opencode.model.QuestionItem(
            id = "q1_objective",
            title = "What would you like to work on first?",
            subtitle = "Select one answer",
            options = listOf(
                QuestionOption(
                    id = "diagnose",
                    title = "Diagnose build / runtime issue",
                    description = "... and want to jump straight to diagnosing it."
                ),
                QuestionOption(
                    id = "feature",
                    title = "Add a new feature",
                    description = "You have a feature in mind and want me to plan its implementation."
                ),
                QuestionOption(
                    id = "refactor",
                    title = "Refactor / clean up",
                    description = "Improve existing code structure, readability, or performance without changing behavior."
                ),
                QuestionOption(
                    id = "custom1",
                    title = "Type your own answer",
                    description = "Type your answer...",
                    isCustomInput = true
                )
            )
        ),
        com.itsaky.androidide.agent.opencode.model.QuestionItem(
            id = "q2_subsystem",
            title = "Which component should we prioritize?",
            subtitle = "Select one answer",
            options = listOf(
                QuestionOption(
                    id = "ui",
                    title = "Compose UI and Screen Layouts",
                    description = "Focus on Material 3 composables, design polish, and interactive views."
                ),
                QuestionOption(
                    id = "logic",
                    title = "ViewModels & State Management",
                    description = "Focus on Kotlin Coroutines, StateFlow, and business logic."
                ),
                QuestionOption(
                    id = "network",
                    title = "OpenCode Server & Network Bridge",
                    description = "Focus on local server communication, SSE streaming, and file bridges."
                ),
                QuestionOption(
                    id = "custom2",
                    title = "Type your own answer",
                    description = "Type your answer...",
                    isCustomInput = true
                )
            )
        ),
        com.itsaky.androidide.agent.opencode.model.QuestionItem(
            id = "q3_mode",
            title = "How should code changes be applied?",
            subtitle = "Select one answer",
            options = listOf(
                QuestionOption(
                    id = "review",
                    title = "Review diffs in Changes tab first",
                    description = "Safe review mode — inspect before applying file modifications."
                ),
                QuestionOption(
                    id = "auto",
                    title = "Direct auto-apply to workspace",
                    description = "Automatically write suggested patches directly to the project files."
                ),
                QuestionOption(
                    id = "custom3",
                    title = "Type your own answer",
                    description = "Type your answer...",
                    isCustomInput = true
                )
            )
        )
    )

    val sampleQuestionCard = MessagePart.QuestionCard(
        questions = sampleQuestions
    )

    Column(modifier = Modifier.padding(14.dp)) {
        InteractiveQuestionCard(
            question = sampleQuestionCard,
            onSubmit = onSubmitAnswer
        )
    }
}

/**
 * Green outlined rolled-back messages banner (matches screenshot 2):
 * "↩ 2 rolled back messages  Ask me an...  ^"
 */
@Composable
private fun RolledBackMessagesBanner(
    rolledBackMessages: List<OpenCodeMessage>,
    onRestore: () -> Unit,
    onDismiss: () -> Unit
) {
    if (rolledBackMessages.isEmpty()) return

    var isExpanded by remember { mutableStateOf(false) }
    val count = rolledBackMessages.size
    val firstText = rolledBackMessages.firstOrNull()?.plainText ?: ""
    val snippet = if (firstText.length > 25) firstText.take(25) + "..." else firstText

    Surface(
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.5.dp, Color(0xFF2E7D32)),
        color = Color.White,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("↩", fontSize = 14.sp, color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "$count rolled back ${if (count == 1) "message" else "messages"}",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.DarkGray
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = snippet.ifBlank { "Ask me an..." },
                    fontSize = 12.sp,
                    color = Color.Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier.size(16.dp)
                )
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    HorizontalDivider(color = Color(0xFFE8F5E9))
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Rewound messages that can be restored or replaced:",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                    Spacer(Modifier.height(4.dp))
                    rolledBackMessages.take(4).forEach { msg ->
                        Row(modifier = Modifier.padding(vertical = 2.dp)) {
                            Text(
                                text = if (msg.role == MessageRole.USER) "You: " else "OpenCode: ",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (msg.role == MessageRole.USER) BrandCyan else Color.DarkGray
                            )
                            Text(
                                text = msg.plainText.take(50).ifEmpty { "(Tool / diff)" },
                                fontSize = 11.sp,
                                color = Color.DarkGray,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("Dismiss", fontSize = 11.sp, color = Color.Gray)
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = onRestore,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.height(30.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                        ) {
                            Text("Restore conversation", fontSize = 11.sp, color = Color.White)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Bottom input card matching screenshot 2:
 * Text area + Bottom bar with:
 * [+] [Plan ⌄] [MiMo-V2.6-Flash Free] [↑ (send)]
 */
@Composable
private fun OpenCodeInputCard(
    text: String,
    onTextChanged: (String) -> Unit,
    isEditing: Boolean = false,
    onCancelEditing: (() -> Unit)? = null,
    activeMode: String,
    showModeDropdown: Boolean,
    onToggleModeDropdown: (Boolean) -> Unit,
    onSelectMode: (String) -> Unit,
    selectedModel: String,
    showModelDropdown: Boolean,
    onToggleModelDropdown: (Boolean) -> Unit,
    onSelectModel: (String) -> Unit,
    isGenerating: Boolean,
    onSend: () -> Unit,
    onCancel: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, if (isEditing) Color(0xFF2E7D32) else Color(0xFFE0E0E0)),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // Editing notice banner if rolling back
            if (isEditing) {
                Surface(
                    color = Color(0xFFE8F5E9),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("↩", fontSize = 12.sp, color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Editing message • Restarts conversation from this point",
                                fontSize = 11.sp,
                                color = Color(0xFF2E7D32),
                                fontWeight = FontWeight.Medium
                            )
                        }
                        if (onCancelEditing != null) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Cancel edit",
                                tint = Color.Gray,
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { onCancelEditing() }
                            )
                        }
                    }
                }
            }

            // Text Input
            OutlinedTextField(
                value = text,
                onValueChange = onTextChanged,
                placeholder = { Text("Ask me anything...", fontSize = 14.sp, color = Color(0xFF9E9E9E)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("chat_input_field"),
                maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent
                )
            )

            Spacer(Modifier.height(8.dp))

            // Bottom controls row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Attach '+' button
                IconButton(onClick = { /* attach context */ }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Add, contentDescription = "Add context", tint = Color.Gray, modifier = Modifier.size(20.dp))
                }

                Spacer(Modifier.width(4.dp))

                // Mode Selector Pill: "Plan ⌄" or "Build ⌄"
                Box {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFFF5F5F5),
                        modifier = Modifier
                            .height(28.dp)
                            .clickable { onToggleModeDropdown(true) }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        ) {
                            Text(text = activeMode, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Color.DarkGray)
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                        }
                    }

                    DropdownMenu(
                        expanded = showModeDropdown,
                        onDismissRequest = { onToggleModeDropdown(false) }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Build", fontSize = 13.sp) },
                            onClick = { onSelectMode("Build") },
                            trailingIcon = { if (activeMode == "Build") Icon(Icons.Default.Check, contentDescription = null, tint = BrandCyan, modifier = Modifier.size(16.dp)) }
                        )
                        DropdownMenuItem(
                            text = { Text("Plan", fontSize = 13.sp) },
                            onClick = { onSelectMode("Plan") },
                            trailingIcon = { if (activeMode == "Plan") Icon(Icons.Default.Check, contentDescription = null, tint = BrandCyan, modifier = Modifier.size(16.dp)) }
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                // Model Selector Pill with Dropdown Menu: "⇄ MiMo-V2.6-Flash Free ⌄"
                Box {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFFF5F5F5),
                        modifier = Modifier
                            .height(28.dp)
                            .clickable { onToggleModelDropdown(true) }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        ) {
                            Text("⇄", fontSize = 12.sp, color = Color.Gray)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = selectedModel,
                                fontSize = 11.sp,
                                color = Color.DarkGray,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                        }
                    }

                    DropdownMenu(
                        expanded = showModelDropdown,
                        onDismissRequest = { onToggleModelDropdown(false) }
                    ) {
                        val grouped = com.itsaky.androidide.agent.opencode.model.OpenCodeFreeProviders.MODELS.groupBy { it.provider }
                        grouped.forEach { (providerName, models) ->
                            Text(
                                text = "— $providerName —",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = BrandCyan,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                            models.forEach { modelItem ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(modelItem.name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                            if (modelItem.description.isNotBlank()) {
                                                Text(modelItem.description, fontSize = 10.sp, color = Color.Gray)
                                            }
                                        }
                                    },
                                    onClick = { onSelectModel(modelItem.name) },
                                    trailingIcon = {
                                        if (selectedModel == modelItem.name || selectedModel == modelItem.id) {
                                            Icon(
                                                Icons.Default.Check,
                                                contentDescription = null,
                                                tint = BrandCyan,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                )
                            }
                            HorizontalDivider(color = Color(0xFFEEEEEE), modifier = Modifier.padding(vertical = 4.dp))
                        }
                    }
                }

                Spacer(Modifier.weight(1f))

                // Send / Stop button
                if (isGenerating) {
                    Button(
                        onClick = onCancel,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Stop", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                } else {
                    Button(
                        onClick = onSend,
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = SubmitBlack),
                        modifier = Modifier.size(36.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                    ) {
                        Text("↑", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * Renders individual chat messages, including text, reasoning, tool calls,
 * patches, and interactive question cards.
 */
@Composable
private fun ChatMessageItem(
    message: OpenCodeMessage,
    onApplyPatch: (MessagePart.FilePatch) -> Unit,
    onRejectPatch: (MessagePart.FilePatch) -> Unit,
    onSubmitQuestion: (String) -> Unit,
    onEditMessage: ((OpenCodeMessage) -> Unit)? = null,
    onToolApproval: ((messageId: String, callId: String, approved: Boolean) -> Unit)? = null
) {
    val isUser = message.role == MessageRole.USER

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        message.parts.forEach { part ->
            when (part) {
                is MessagePart.ToolApproval -> {
                    HumanInTheLoopToolApprovalCard(
                        approval = part.request,
                        onApprove = { onToolApproval?.invoke(message.id, part.request.callId, true) },
                        onReject = { onToolApproval?.invoke(message.id, part.request.callId, false) },
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                is MessagePart.QuestionCard -> {
                    InteractiveQuestionCard(
                        question = part,
                        onSubmit = onSubmitQuestion
                    )
                }
                is MessagePart.FilePatch -> {
                    FilePatchCard(part, onApplyPatch, onRejectPatch)
                }
                is MessagePart.ToolCall -> {
                    ToolCallBox(part)
                }
                is MessagePart.Reasoning -> {
                    ReasoningBox(part.reasoningText)
                }
                is MessagePart.Text -> {
                    if (part.content.isNotEmpty()) {
                        Column(
                            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
                            modifier = Modifier.widthIn(max = 520.dp)
                        ) {
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isUser) BrandCyan else Color(0xFFF5F5F5)
                                )
                            ) {
                                Text(
                                    text = part.content,
                                    color = if (isUser) Color.White else Color.Black,
                                    fontSize = 14.sp,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }

                            if (isUser && onEditMessage != null) {
                                Row(
                                    modifier = Modifier
                                        .padding(top = 4.dp, end = 2.dp)
                                        .clickable { onEditMessage(message) },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = "Edit and restart conversation",
                                        tint = Color(0xFF00ACC1),
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = "Edit & Restart",
                                        fontSize = 11.sp,
                                        color = Color(0xFF00ACC1),
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReasoningBox(reasoning: String) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFFF9F9F9),
        border = BorderStroke(1.dp, Color(0xFFEEEEEE)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Reasoning Trace", style = MaterialTheme.typography.labelSmall, color = BrandCyan)
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide" else "Show", fontSize = 11.sp)
                }
            }
            if (expanded) {
                Text(
                    text = reasoning,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.DarkGray,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun ToolCallBox(tool: MessagePart.ToolCall) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFFF5F5F5),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Tool: ", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
            Text(tool.toolName, style = MaterialTheme.typography.labelSmall, color = BrandCyan)
            if (tool.arguments.isNotEmpty()) {
                Text(" (${tool.arguments.take(40)}...)", style = MaterialTheme.typography.bodySmall, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun FilePatchCard(
    patch: MessagePart.FilePatch,
    onApply: (MessagePart.FilePatch) -> Unit,
    onReject: (MessagePart.FilePatch) -> Unit
) {
    Card(
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, DarkCardBorder),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = patch.relativeFilePath,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Status: ${patch.status.name}",
                        style = MaterialTheme.typography.labelSmall,
                        color = when (patch.status) {
                            PatchStatus.APPLIED -> Color(0xFF2E7D32)
                            PatchStatus.REJECTED -> Color(0xFFD32F2F)
                            PatchStatus.PENDING -> BrandCyan
                        }
                    )
                }

                if (patch.status == PatchStatus.PENDING) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = { onReject(patch) },
                            modifier = Modifier.height(32.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
                        ) {
                            Text("Discard", fontSize = 11.sp)
                        }
                        Button(
                            onClick = { onApply(patch) },
                            modifier = Modifier.height(32.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = SubmitBlack),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
                        ) {
                            Text("Apply", fontSize = 11.sp, color = Color.White)
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Surface(
                color = Color(0xFF1E1E1E),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
            ) {
                val scroll = rememberScrollState()
                Column(modifier = Modifier.padding(6.dp).horizontalScroll(scroll)) {
                    if (patch.diffLines.isEmpty()) {
                        Text(
                            text = patch.proposedContent.take(300),
                            color = Color(0xFFE0E0E0),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                    } else {
                        patch.diffLines.take(40).forEach { line ->
                            val linePrefix = when (line.type) {
                                DiffType.ADDITION -> "+ "
                                DiffType.DELETION -> "- "
                                DiffType.CONTEXT -> "  "
                            }
                            val textColor = when (line.type) {
                                DiffType.ADDITION -> Color(0xFF81C784)
                                DiffType.DELETION -> Color(0xFFE57373)
                                DiffType.CONTEXT -> Color(0xFFB0BEC5)
                            }
                            Text(
                                text = "$linePrefix${line.text}",
                                color = textColor,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Changes tab showing all pending/applied code changes across the project.
 */
@Composable
private fun ChangesReviewPane(
    messages: List<OpenCodeMessage>,
    onApplyPatch: (String, MessagePart.FilePatch) -> Unit,
    onRejectPatch: (String, MessagePart.FilePatch) -> Unit
) {
    val allPatches = messages.flatMap { msg ->
        msg.parts.filterIsInstance<MessagePart.FilePatch>().map { msg.id to it }
    }

    if (allPatches.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("No pending or recorded file changes in this session", color = Color.Gray, fontSize = 13.sp)
        }
    } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(allPatches) { (msgId, patch) ->
                FilePatchCard(
                    patch = patch,
                    onApply = { onApplyPatch(msgId, it) },
                    onReject = { onRejectPatch(msgId, it) }
                )
            }
        }
    }
}
