package com.noop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.input.pointer.pointerInput
import com.noop.R

/** Mode and category are independent: changing the time mode keeps the chosen category. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OuraExplorerNavigation(mode: OuraViewMode, category: OuraCategory,
    onMode: (OuraViewMode) -> Unit, onCategory: (OuraCategory) -> Unit) {
    val currentMode by rememberUpdatedState(mode)
    val currentOnMode by rememberUpdatedState(onMode)
    val liveLabel = stringResource(R.string.nav_live)
    val historyLabel = stringResource(R.string.oura_history)
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
        if (category != OuraCategory.Raw) OuraToolbar(items = OuraViewMode.entries, selection = mode,
            label = { if (it == OuraViewMode.Live) liveLabel else historyLabel },
            onSelect = onMode, modifier = Modifier.fillMaxWidth().pointerInput(Unit) {
                fun select(x: Float) {
                    val target = if (x < size.width / 2f) OuraViewMode.History else OuraViewMode.Live
                    if (target != currentMode) currentOnMode(target)
                }
                detectHorizontalDragGestures(onDragStart = { select(it.x) }) { change, _ ->
                    change.consume(); select(change.position.x)
                }
            },
            accessibilityLabel = stringResource(R.string.oura_view_mode))
        var expanded by remember { mutableStateOf(false) }
        val categoryLabel = stringResource(R.string.oura_category)
        val expandedLabel = stringResource(if (expanded) R.string.oura_expanded else R.string.oura_collapsed)
        Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { expanded = !expanded }, shape = RoundedCornerShape(Metrics.cornerXs),
                modifier = Modifier.fillMaxWidth().heightIn(min = Metrics.space24 * 2).testTag("oura-category-picker").semantics {
                    contentDescription = categoryLabel
                    stateDescription = expandedLabel
                }) {
                Text(categoryLabel + ": " + stringResource(ouraCategoryLabel(category)),
                    Modifier.weight(1f), style = NoopType.subhead)
                Icon(Icons.Filled.ExpandMore, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
                modifier = Modifier.heightIn(max = Metrics.dialogScrollableMaxHeight)) {
                OuraCategory.entries.forEach { item ->
                    DropdownMenuItem(text = { Text(stringResource(ouraCategoryLabel(item)), style = NoopType.body) },
                        modifier = Modifier.testTag("oura-category-${item.name}").semantics { selected = item == category },
                        onClick = { expanded = false; onCategory(item) })
                }
            }
        }
    }
}

internal fun ouraCategoryLabel(item: OuraCategory): Int = when (item) {
    OuraCategory.Health -> R.string.nav_health
    OuraCategory.Sleep -> R.string.nav_sleep
    OuraCategory.Movement -> R.string.oura_tab_movement
    OuraCategory.Compare -> R.string.nav_compare
    OuraCategory.Raw -> R.string.oura_raw_category
}
