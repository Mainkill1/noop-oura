package com.noop.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import com.noop.R
import com.noop.data.OuraDashboardSource
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun OuraControls(
    sources: List<OuraDashboardSource>, sourceId: String, day: LocalDate, days: Int,
    showRange: Boolean, loading: Boolean,
    onSource: (String) -> Unit, onDay: (LocalDate) -> Unit, onDays: (Int) -> Unit,
    onRanges: (Boolean) -> Unit, onRefresh: () -> Unit, onDevices: () -> Unit, onImport: () -> Unit,
    onKeys: () -> Unit = {}, isLive: Boolean = false,
) {
    val context = LocalContext.current
    var sourceMenu by remember { mutableStateOf(false) }
    var actionsMenu by remember { mutableStateOf(false) }
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    TextButton(onClick = { sourceMenu = true }, enabled = sources.isNotEmpty()) {
                        Text(sources.firstOrNull { it.id == sourceId }?.name ?: stringResource(R.string.oura_source),
                            modifier = Modifier.weight(1f, fill = false), maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = Palette.textPrimary)
                        Icon(Icons.Filled.ExpandMore, contentDescription = null)
                    }
                    DropdownMenu(expanded = sourceMenu, onDismissRequest = { sourceMenu = false }) {
                        sources.forEach { source ->
                            DropdownMenuItem(text = { Text(source.name) }, onClick = { onSource(source.id); sourceMenu = false })
                        }
                    }
                }
                IconButton(onClick = onRefresh, enabled = !loading) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.oura_refresh))
                }
                Box {
                    IconButton(onClick = { actionsMenu = true }) {
                        Icon(Icons.Filled.MoreHoriz, contentDescription = stringResource(R.string.nav_more))
                    }
                    DropdownMenu(expanded = actionsMenu, onDismissRequest = { actionsMenu = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.oura_keys_title)) },
                            onClick = { actionsMenu = false; onKeys() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.oura_manage)) },
                            onClick = { actionsMenu = false; onDevices() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.oura_import)) },
                            onClick = { actionsMenu = false; onImport() })
                    }
                }
            }
            if (!isLive) {
                HorizontalDivider(color = Palette.hairline)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onDay(day.minusDays(days.toLong())) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.oura_previous))
                    }
                    TextButton(modifier = Modifier.weight(1f), onClick = {
                        DatePickerDialog(context, { _, year, month, date -> onDay(LocalDate.of(year, month + 1, date)) },
                            day.year, day.monthValue - 1, day.dayOfMonth).apply {
                            datePicker.maxDate = System.currentTimeMillis()
                        }.show()
                    }) {
                        Text(day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)), style = NoopType.bodyNumber,
                            color = Palette.textPrimary)
                    }
                    IconButton(onClick = { onDay(minOf(day.plusDays(days.toLong()), LocalDate.now())) }, enabled = day < LocalDate.now()) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = stringResource(R.string.oura_next))
                    }
                }
                SegmentedPillControl(items = listOf(1, 7, 30), selection = days,
                    label = { context.getString(if (it == 1) R.string.oura_one_day else R.string.oura_days, it) },
                    onSelect = onDays, adaptsToAvailableWidth = true, modifier = Modifier.fillMaxWidth(),
                    accessibilityLabel = stringResource(R.string.oura_window))
                if (days > 1) Text(
                    day.minusDays(days.toLong() - 1).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) +
                        " – " + day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
                    style = NoopType.captionNumber, color = Palette.textSecondary)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).toggleable(value = showRange, role = Role.Switch, onValueChange = onRanges),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                    Switch(checked = showRange, onCheckedChange = null)
                    Text(stringResource(R.string.oura_show_ranges), style = NoopType.caption, color = Palette.textSecondary)
                }
                if (!isLive && day != LocalDate.now()) TextButton(onClick = { onDay(LocalDate.now()) }) {
                    Text(stringResource(R.string.oura_today))
                }
            }
            // Reserve the progress slot so refresh never moves the surrounding controls.
            Box(Modifier.fillMaxWidth().height(Metrics.space4)) {
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Palette.accent)
            }
        }
    }
}
