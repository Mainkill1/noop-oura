package com.noop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.noop.R
import com.noop.analytics.SignalDecimation
import com.noop.ble.OuraHistorySyncPhase
import java.time.Instant
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun OuraDetailsDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = Palette.surfaceRaised, shape = RoundedCornerShape(Metrics.cornerXs),
        title = { Text(title, style = NoopType.headline, color = Palette.textPrimary) },
        text = {
            Column(Modifier.heightIn(max = Metrics.dialogScrollableMaxHeight).verticalScroll(rememberScrollState())
                .testTag("oura-details-content"), verticalArrangement = Arrangement.spacedBy(Metrics.space12), content = content)
        }, confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("oura-details-close")) {
                Text(stringResource(R.string.oura_details_close))
            }
        })
}

@Composable
internal fun OuraHistorySmoothing(strength: Int, onChange: (Int) -> Unit) {
    val label = stringResource(R.string.oura_smoothing)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space8)) {
        Text(stringResource(R.string.oura_smoothing), style = NoopType.caption, color = Palette.textSecondary)
        Slider(value = strength.toFloat(), onValueChange = { onChange(it.roundToInt().coerceIn(0, 4)) },
            valueRange = 0f..4f, steps = 3, modifier = Modifier.weight(1f).testTag("oura-history-smoothing").semantics { contentDescription = label },
            colors = SliderDefaults.colors(thumbColor = Palette.accent, activeTrackColor = Palette.accent,
                inactiveTrackColor = Palette.hairline))
        Text(if (strength == 0) stringResource(R.string.oura_smoothing_off) else listOf(1, 3, 5, 9, 15)[strength.coerceIn(0, 4)].toString(),
            style = NoopType.captionNumber, color = Palette.textSecondary)
    }
}

@Composable
internal fun OuraSummaryDetails(summary: SignalDecimation.Summary, unit: String, note: String,
    scale: Double = 1.0, offset: Double = 0.0, smoothing: Int = 0) {
    fun value(number: Double?) = number?.let { String.format(Locale.getDefault(), "%.1f %s", it * scale + offset, unit) } ?: "—"
    Text(stringResource(R.string.oura_detail_summary, value(summary.minimum), value(summary.maximum), value(summary.mean)),
        style = NoopType.bodyNumber, color = Palette.textPrimary)
    Text(stringResource(R.string.oura_reading_counts, summary.count, summary.buckets.size),
        style = NoopType.captionNumber, color = Palette.textSecondary)
    Text(stringResource(R.string.oura_detail_latest_value, value(summary.latestValue)),
        style = NoopType.captionNumber, color = Palette.textPrimary)
    val time = rememberOuraTimestampFormatter(seconds = true)
    summary.latestTs?.let { Text(stringResource(R.string.oura_detail_latest_measurement, time.format(Instant.ofEpochSecond(it))),
        style = NoopType.captionNumber, color = Palette.textSecondary) }
    Text(stringResource(R.string.oura_detail_calculation), style = NoopType.caption, color = Palette.textSecondary)
    if (smoothing > 0) Text(stringResource(R.string.oura_detail_smoothing, listOf(1, 3, 5, 9, 15)[smoothing.coerceIn(0, 4)]),
        style = NoopType.caption, color = Palette.textSecondary)
    if (note.isNotBlank()) Text(note, style = NoopType.caption, color = Palette.textSecondary)
}

@Composable
internal fun OuraAcquisitionDetails(source: String?, loadedAt: Long?, imported: Boolean, history: OuraHistorySyncPhase?, transfer: com.noop.ble.OuraHistoryTransferSnapshot? = null) {
    source?.let { Text(stringResource(R.string.oura_detail_source, it), style = NoopType.subhead, color = Palette.textPrimary) }
    val time = rememberOuraTimestampFormatter(seconds = true)
    loadedAt?.let { Text(stringResource(R.string.oura_detail_loaded, time.format(Instant.ofEpochMilli(it))),
        style = NoopType.captionNumber, color = Palette.textSecondary) }
    Text(stringResource(if (imported) R.string.oura_import_note else R.string.oura_detail_download_unknown),
        style = NoopType.caption, color = Palette.textSecondary)
    if (!imported) OuraHistoryTransferDetails(transfer)
    if (!imported && history != null) Text(stringResource(when (history) {
        OuraHistorySyncPhase.SYNCING -> R.string.oura_live_history_syncing
        OuraHistorySyncPhase.PAUSED_FOR_LIVE -> R.string.oura_live_history_paused
        OuraHistorySyncPhase.IDLE -> R.string.oura_live_history_idle
    }), style = NoopType.caption, color = Palette.textSecondary)
}

/** Session recap describes observed traffic, never the provenance of every displayed measurement. */
@Composable
internal fun OuraHistoryTransferDetails(transfer: com.noop.ble.OuraHistoryTransferSnapshot?) {
    val recap = transfer?.takeIf { it.startedAtMs != null } ?: return
    val time = rememberOuraTimestampFormatter(seconds = true)
    Text(stringResource(R.string.oura_transfer_title), style = NoopType.subhead, color = Palette.textPrimary)
    Text(stringResource(when (recap.outcome) {
        com.noop.ble.OuraHistoryTransferOutcome.ACTIVE -> R.string.oura_transfer_active
        com.noop.ble.OuraHistoryTransferOutcome.COMPLETE -> R.string.oura_transfer_complete
        com.noop.ble.OuraHistoryTransferOutcome.YIELDED -> R.string.oura_transfer_yielded
        com.noop.ble.OuraHistoryTransferOutcome.DISCONNECTED -> R.string.oura_transfer_disconnected
        else -> R.string.oura_transfer_stopped
    }), style = NoopType.caption, color = Palette.textSecondary)
    Text(stringResource(R.string.oura_transfer_started, time.format(Instant.ofEpochMilli(recap.startedAtMs!!))),
        style = NoopType.captionNumber, color = Palette.textSecondary)
    recap.endedAtMs?.let { Text(stringResource(R.string.oura_transfer_ended, time.format(Instant.ofEpochMilli(it))),
        style = NoopType.captionNumber, color = Palette.textSecondary) }
    Text(stringResource(R.string.oura_transfer_rate, recap.elapsedMs / 1000.0, recap.wireBytes,
        if (recap.elapsedMs > 0) recap.wireBytes * 1000.0 / recap.elapsedMs else 0.0),
        style = NoopType.captionNumber, color = Palette.textPrimary)
    Text(stringResource(R.string.oura_transfer_notifications, recap.notifications, recap.maxNotificationBytes),
        style = NoopType.captionNumber, color = Palette.textSecondary)
    Text(stringResource(R.string.oura_transfer_sizes, recap.notificationSizes[0], recap.notificationSizes[1], recap.notificationSizes[2]),
        style = NoopType.captionNumber, color = Palette.textSecondary)
    Text(stringResource(R.string.oura_transfer_frames, recap.outerFrames, recap.packedNotifications),
        style = NoopType.captionNumber, color = Palette.textSecondary)
    Text(stringResource(R.string.oura_transfer_records, recap.records, recap.decodedEvents),
        style = NoopType.captionNumber, color = Palette.textSecondary)
    fun phy(value: Int?) = when (value) { 1 -> "1M"; 2 -> "2M"; 3 -> "LE Coded"; else -> "—" }
    Text(stringResource(R.string.oura_transfer_link, recap.mtu, phy(recap.txPhy), phy(recap.rxPhy)),
        style = NoopType.captionNumber, color = Palette.textSecondary)
    Text(stringResource(R.string.oura_transfer_priority, recap.boostRequested.toString(), recap.priorityRequestAccepted?.toString() ?: "—",
        recap.balancedRequestAccepted?.toString() ?: "—", recap.phyRestoreSubmitted?.toString() ?: "—"),
        style = NoopType.captionNumber, color = Palette.textSecondary)
    Text(stringResource(R.string.oura_transfer_note), style = NoopType.caption, color = Palette.textSecondary)
}
