package com.itsaky.androidide.agent.opencode.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itsaky.androidide.agent.opencode.acp.AcpConfigOption

private val PillBackground = Color(0xFFF5F5F5)
private val AccentBlue = Color(0xFF0091EA)
private const val SEARCH_THRESHOLD = 12

/**
 * A pill ("Build ⌄", "MiMo-V2.6-Flash Free ⌄") that lists the values of one ACP config option
 * (mode = Build/Plan, model, ...) and reports the chosen value.
 *
 * - Values come from the agent, never from a hard-coded list.
 * - Long lists (models) get a search field; model values are grouped by provider.
 * - While the agent has not reported the option yet, the pill shows [placeholder] and is disabled.
 */
@Composable
fun ConfigSelectorPill(
    option: AcpConfigOption?,
    placeholder: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    leadingGlyph: String? = null,
    fontSize: TextUnit = 12.sp
) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val enabled = option != null && option.values.isNotEmpty()

    Box(modifier) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = PillBackground,
            modifier = Modifier
                .height(28.dp)
                .alpha(if (enabled) 1f else 0.5f)
                .clickable(enabled = enabled) {
                    query = ""
                    expanded = true
                }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp)
            ) {
                if (leadingGlyph != null) {
                    Text(leadingGlyph, fontSize = 12.sp, color = Color.Gray)
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = option?.currentName ?: placeholder,
                    fontSize = fontSize,
                    fontWeight = FontWeight.Medium,
                    color = Color.DarkGray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 170.dp)
                )
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        DropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 380.dp)
        ) {
            if (option == null) return@DropdownMenu

            if (option.values.size > SEARCH_THRESHOLD) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search", fontSize = 13.sp) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            val shown = if (query.isBlank()) option.values else option.values.filter {
                it.name.contains(query, ignoreCase = true) ||
                    it.value.contains(query, ignoreCase = true) ||
                    it.description?.contains(query, ignoreCase = true) == true
            }

            var lastGroup: String? = null
            shown.forEach { item ->
                // Models arrive as "provider/model": use the provider as a section header.
                val group = item.group
                    ?: item.value.substringBefore('/', "").takeIf { option.isModel && it.isNotEmpty() }
                if (group != null && group != lastGroup) {
                    Text(
                        text = group,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentBlue,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                    lastGroup = group
                }
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(item.name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            item.description?.takeIf { it.isNotBlank() }?.let {
                                Text(it, fontSize = 10.sp, color = Color.Gray, maxLines = 2)
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        if (item.value != option.currentValue) onSelect(item.value)
                    },
                    trailingIcon = {
                        if (item.value == option.currentValue) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Selected",
                                tint = AccentBlue,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                )
            }
        }
    }
}
