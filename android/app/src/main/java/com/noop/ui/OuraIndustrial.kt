package com.noop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.noop.R

/** Oura's compact instrument surface, using shared palette, spacing and radius tokens. */
@Composable
internal fun OuraPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(Metrics.cornerXs),
        color = Palette.surfaceRaised, border = BorderStroke(Metrics.divider, Palette.hairline)) {
        Box(Modifier.padding(Metrics.space12)) { content() }
    }
}

/** Rectangular, wrapping toolbar; touch areas remain at least the Material minimum. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun <T> OuraToolbar(items: List<T>, selection: T, label: (T) -> String, onSelect: (T) -> Unit,
    modifier: Modifier = Modifier, accessibilityLabel: String = "") {
    Row(modifier.fillMaxWidth().selectableGroup().semantics { contentDescription = accessibilityLabel },
        horizontalArrangement = Arrangement.spacedBy(Metrics.space4)) {
        items.forEach { item ->
            Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(Metrics.cornerXs),
                color = if (item == selection) Palette.surfaceOverlay else Palette.surfaceInset,
                border = BorderStroke(Metrics.divider, if (item == selection) Palette.accent else Palette.hairline)) {
                Box(Modifier.defaultMinSize(minWidth = (Metrics.space24 * 2), minHeight = (Metrics.space24 * 2))
                    .selectable(item == selection, role = Role.RadioButton, onClick = { onSelect(item) })
                    .padding(horizontal = Metrics.space12, vertical = Metrics.space8), contentAlignment = Alignment.Center) {
                    Text(label(item), style = NoopType.subhead, color = if (item == selection) Palette.textPrimary else Palette.textSecondary)
                }
            }
        }
    }
}
