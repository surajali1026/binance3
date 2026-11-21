package com.example.ui.chart

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.domain.indicator.TbtMtfHubTab
import com.example.ui.theme.AmberGold
import com.example.ui.theme.BearishRed
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.BullishGreen
import com.example.ui.theme.CanvasVoid
import com.example.ui.theme.CyanInfo
import com.example.ui.theme.ElevatedSurface
import com.example.ui.theme.TabularMonoStyle
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.ToolbarSurface

private val QUICK_MTF_CHIPS = listOf(
    "1D", "4H", "2H", "1H", "30m", "15m", "10m", "5m", "3m", "2m", "1m"
)

/**
 * Dialog allowing traders to create custom Multi-Timeframe Content Hub tabs
 * (e.g., a "30m" tab with 30m, 15m, 10m, 5m, 2m) and manage existing custom tabs.
 */
@Composable
fun AddCustomMtfHubTabDialog(
    customTabs: List<TbtMtfHubTab>,
    onAddTab: (name: String, timeframeIds: List<String>) -> Unit,
    onDeleteTab: (tabId: String) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        AddCustomMtfHubTabContent(
            customTabs = customTabs,
            onAddTab = onAddTab,
            onDeleteTab = onDeleteTab,
            onDismiss = onDismiss
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddCustomMtfHubTabContent(
    customTabs: List<TbtMtfHubTab>,
    onAddTab: (name: String, timeframeIds: List<String>) -> Unit,
    onDeleteTab: (tabId: String) -> Unit,
    onDismiss: () -> Unit
) {
    var tabName by remember { mutableStateOf("") }
    var customInput by remember { mutableStateOf("") }
    var selectedTfs by remember { mutableStateOf(listOf("30m", "15m", "10m", "5m", "2m")) }
    var errorText by remember { mutableStateOf<String?>(null) }

    fun toggleTf(tf: String) {
        errorText = null
        val exists = selectedTfs.any { it.equals(tf, ignoreCase = true) }
        selectedTfs = if (exists) {
            selectedTfs.filterNot { it.equals(tf, ignoreCase = true) }
        } else {
            (selectedTfs + tf)
                .distinctBy { it.lowercase() }
                .sortedByDescending { TbtMtfHubTab.durationMillisOf(it) }
        }
    }

    fun applyCustomInput() {
        val parsed = TbtMtfHubTab.parseTimeframeList(customInput)
        if (parsed.isEmpty()) {
            errorText = "Enter minutes/hours like 30, 15, 10, 5, 2 or 45m, 2H"
            return
        }
        errorText = null
        selectedTfs = (selectedTfs + parsed)
            .distinctBy { it.lowercase() }
            .sortedByDescending { TbtMtfHubTab.durationMillisOf(it) }
        customInput = ""
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("custom_mtf_hub_dialog"),
        shape = RoundedCornerShape(14.dp),
        color = ElevatedSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Custom MTF Hub Tab",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Shows Bullish / Bearish per TF without switching chart",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                        fontSize = 10.5.sp
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(30.dp)
                        .testTag("custom_mtf_dialog_close")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close Custom Hub Tab Dialog",
                        tint = TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            // Tab Name Input
            OutlinedTextField(
                value = tabName,
                onValueChange = { tabName = it.take(12) },
                label = { Text("Tab Name (e.g. 30m, 15m, Scalp)", fontSize = 11.sp) },
                placeholder = { Text(selectedTfs.firstOrNull() ?: "30m", fontSize = 11.sp) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AmberGold,
                    unfocusedBorderColor = BorderSubtle,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    focusedLabelColor = AmberGold,
                    unfocusedLabelColor = TextMuted
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("custom_mtf_tab_name_input")
            )

            // Quick Select Timeframe Chips
            Text(
                text = "TAP TIMEFRAMES TO INCLUDE",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                QUICK_MTF_CHIPS.forEach { tf ->
                    val isSelected = selectedTfs.any { it.equals(tf, ignoreCase = true) }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isSelected) AmberGold.copy(alpha = 0.22f) else ToolbarSurface)
                            .border(
                                0.75.dp,
                                if (isSelected) AmberGold else BorderSubtle,
                                RoundedCornerShape(6.dp)
                            )
                            .clickable { toggleTf(tf) }
                            .padding(horizontal = 9.dp, vertical = 6.dp)
                            .testTag("custom_mtf_chip_${tf.lowercase()}")
                    ) {
                        Text(
                            text = tf,
                            style = TabularMonoStyle.copy(
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium
                            ),
                            color = if (isSelected) AmberGold else TextPrimary
                        )
                    }
                }
            }

            // Freeform Custom Sequence Input (e.g., "30 15 10 5 2" or "7m, 20m")
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = customInput,
                    onValueChange = {
                        customInput = it
                        errorText = null
                    },
                    label = { Text("Add custom (e.g. 2m, 7m or 30 15 10 5 2)", fontSize = 10.5.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanInfo,
                        unfocusedBorderColor = BorderSubtle,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedLabelColor = CyanInfo,
                        unfocusedLabelColor = TextMuted
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("custom_mtf_tfs_input")
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(CyanInfo.copy(alpha = 0.20f))
                        .border(0.75.dp, CyanInfo, RoundedCornerShape(8.dp))
                        .clickable { applyCustomInput() }
                        .padding(horizontal = 10.dp, vertical = 14.dp)
                        .testTag("custom_mtf_add_tf_btn"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "Add custom timeframe",
                        tint = CyanInfo,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            if (errorText != null) {
                Text(
                    text = errorText!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = BearishRed,
                    fontSize = 10.5.sp
                )
            }

            // Selected Preview Strip
            if (selectedTfs.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(CanvasVoid.copy(alpha = 0.7f))
                        .border(0.5.dp, BorderSubtle, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Preview: ${selectedTfs.joinToString(" → ")}",
                        style = TabularMonoStyle.copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold),
                        color = BullishGreen,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "Clear",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .clickable { selectedTfs = emptyList() }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }

            // Save Button
            Button(
                onClick = {
                    val extraParsed = if (customInput.isNotBlank()) {
                        TbtMtfHubTab.parseTimeframeList(customInput)
                    } else emptyList()
                    val finalTfs = (selectedTfs + extraParsed)
                        .distinctBy { it.lowercase() }
                        .sortedByDescending { TbtMtfHubTab.durationMillisOf(it) }
                    if (finalTfs.isEmpty()) {
                        errorText = "Select or enter at least 1 timeframe"
                        return@Button
                    }
                    val resolvedName = tabName.trim().ifBlank { finalTfs.first() }
                    onAddTab(resolvedName, finalTfs)
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = AmberGold,
                    contentColor = CanvasVoid
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .testTag("custom_mtf_save_btn")
            ) {
                Text("Save Hub Tab", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
            }

            // Saved Custom Tabs List (with 1-tap delete)
            if (customTabs.isNotEmpty()) {
                HorizontalDivider(color = BorderSubtle, thickness = 0.5.dp)
                Text(
                    text = "SAVED CUSTOM TABS",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    customTabs.forEach { tab ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(7.dp))
                                .background(ToolbarSurface)
                                .border(0.5.dp, BorderSubtle, RoundedCornerShape(7.dp))
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = tab.name,
                                style = TabularMonoStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                                color = AmberGold
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = tab.timeframeIds.joinToString(", "),
                                style = TabularMonoStyle.copy(fontSize = 10.sp),
                                color = TextMuted,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { onDeleteTab(tab.id) },
                                modifier = Modifier
                                    .size(28.dp)
                                    .testTag("custom_mtf_delete_${tab.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.DeleteOutline,
                                    contentDescription = "Delete ${tab.name} tab",
                                    tint = BearishRed,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
