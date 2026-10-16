package com.noop.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.noop.R
import com.noop.ble.OuraLiveHrSample
import com.noop.ble.ouraLiveDelivery

@Composable
internal fun OuraLiveDeliveryDetails(samples: List<OuraLiveHrSample>) {
    if (samples.isEmpty()) return
    val timing = ouraLiveDelivery(samples)
    Text(stringResource(R.string.oura_delivery_title), style = NoopType.subhead, color = Palette.textPrimary)
    Text(stringResource(R.string.oura_delivery_counts, timing.readings, timing.notifications?.toString() ?: "—",
        timing.maxReadingsPerNotification?.toString() ?: "—"), style = NoopType.captionNumber, color = Palette.textSecondary)
    Text(stringResource(R.string.oura_delivery_gaps, timing.lastReceiveGapMs?.toString() ?: "—",
        timing.minReceiveGapMs?.toString() ?: "—", timing.maxReceiveGapMs?.toString() ?: "—"),
        style = NoopType.captionNumber, color = Palette.textSecondary)
    Text(stringResource(R.string.oura_delivery_queue, timing.lastQueueWaitMs?.toString() ?: "—", timing.maxQueueWaitMs?.toString() ?: "—"),
        style = NoopType.captionNumber, color = Palette.textSecondary)
    if (timing.outOfOrderReceipts > 0) Text(stringResource(R.string.oura_delivery_reordered, timing.outOfOrderReceipts),
        style = NoopType.captionNumber, color = Palette.textSecondary)
    Text(stringResource(R.string.oura_delivery_note), style = NoopType.caption, color = Palette.textSecondary)
    Text(stringResource(R.string.oura_delivery_candidate), style = NoopType.caption, color = Palette.textSecondary)
}
