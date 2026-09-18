package com.itsaky.androidide.opencode

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun OpenCodeChatScreen(
	viewModel: OpenCodeChatViewModel,
) {
	val state by viewModel.uiState.collectAsState()
	var draft by remember { mutableStateOf("") }
	val clipboardManager = LocalClipboardManager.current
	val isBusy = (state as? OpenCodeChatUiState.Ready)?.waitingForResponse == true

	Column(modifier = Modifier.fillMaxSize()) {
		Row(
			modifier = Modifier.fillMaxWidth().padding(12.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.SpaceBetween,
		) {
			Text(text = "OpenCode Agent", fontSize = 18.sp)
			Button(onClick = { viewModel.clearChat() }) { Text("Clear") }
		}

		Divider()

		LazyColumn(
			modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
		) {
			when (val ui = state) {
				is OpenCodeChatUiState.Ready -> {
					items(ui.messages) { message ->
						OpenCodeChatBubble(
							message = message,
							onCopy = { clipboardManager.setText(AnnotatedString(message.content)) },
							onEdit = { draft = message.content },
						)
					}
					if (ui.waitingForResponse) {
						item {
							Box(
								modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
								contentAlignment = Alignment.Center,
							) {
								CircularProgressIndicator()
							}
						}
					}
				}
				is OpenCodeChatUiState.Error -> {
					item {
						Text(
							text = ui.message,
							color = MaterialTheme.colorScheme.error,
							modifier = Modifier.padding(vertical = 12.dp),
						)
					}
				}
				OpenCodeChatUiState.Idle -> Unit
			}
		}

		Divider()

		Row(
			modifier = Modifier.fillMaxWidth().padding(12.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			TextField(
				value = draft,
				onValueChange = { draft = it },
				placeholder = { Text("Ask OpenCode to inspect or edit your project...") },
				modifier = Modifier.weight(1f).heightIn(min = 56.dp, max = 120.dp),
				shape = RoundedCornerShape(12.dp),
			)
			Spacer(modifier = Modifier.height(8.dp))
			IconButton(
				onClick = {
					if (draft.isNotBlank()) {
						viewModel.sendMessage(draft)
						draft = ""
					}
				},
				enabled = draft.isNotBlank() && !isBusy,
			) {
				Icon(Icons.Filled.Send, contentDescription = "Send message")
			}
		}
	}
}

@Composable
private fun OpenCodeChatBubble(
	message: OpenCodeChatMessage,
	onCopy: () -> Unit,
	onEdit: () -> Unit,
) {
	val bubbleColor = if (message.isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
	val textColor = if (message.isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

	Row(
		modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
		horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start,
	) {
		Surface(
			shape = RoundedCornerShape(12.dp),
			color = bubbleColor,
			modifier = Modifier.widthIn(max = 320.dp).padding(4.dp),
		) {
			Column(modifier = Modifier.padding(12.dp)) {
				Text(
					text = if (message.isUser) "You" else "AI",
					style = MaterialTheme.typography.labelSmall,
					color = textColor.copy(alpha = 0.8f),
				)
				Text(text = message.content, color = textColor)
				if (!message.isUser) {
					Row(
						modifier = Modifier.fillMaxWidth(),
						horizontalArrangement = Arrangement.End,
					) {
						IconButton(onClick = onCopy) {
							Icon(Icons.Filled.ContentCopy, contentDescription = "Copy response")
						}
					}
				}
				if (message.isUser) {
					Row(
						modifier = Modifier.fillMaxWidth(),
						horizontalArrangement = Arrangement.End,
					) {
						IconButton(onClick = onEdit) {
							Icon(Icons.Filled.Edit, contentDescription = "Edit message")
						}
					}
				}
			}
		}
	}
}
