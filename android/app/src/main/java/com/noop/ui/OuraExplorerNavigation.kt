package com.noop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
        SegmentedPillControl(items = OuraViewMode.entries, selection = mode,
            label = { if (it == OuraViewMode.Live) liveLabel else historyLabel },
            onSelect = onMode, adaptsToAvailableWidth = true, modifier = Modifier.fillMaxWidth().pointerInput(Unit) {
                fun select(x: Float) {
                    val target = if (x < size.width / 2f) OuraViewMode.Live else OuraViewMode.History
                    if (target != currentMode) currentOnMode(target)
                }
                detectHorizontalDragGestures(onDragStart = { select(it.x) }) { change, _ ->
                    change.consume(); select(change.position.x)
                }
            },
            accessibilityLabel = stringResource(R.string.oura_view_mode))
        ScrollableTabRow(selectedTabIndex = category.ordinal, containerColor = Palette.surfaceBase,
            contentColor = Palette.accent, edgePadding = Metrics.space4,
            divider = { HorizontalDivider(color = Palette.hairline) }) {
            OuraCategory.entries.forEach { item ->
                Tab(selected = item == category, onClick = { onCategory(item) },
                    selectedContentColor = Palette.accent, unselectedContentColor = Palette.textSecondary,
                    text = { Text(stringResource(when (item) {
                        OuraCategory.Health -> R.string.nav_health
                        OuraCategory.Sleep -> R.string.nav_sleep
                        OuraCategory.Movement -> R.string.oura_tab_movement
                        OuraCategory.Compare -> R.string.nav_compare
                    }), style = NoopType.subhead) })
            }
        }
    }
}
