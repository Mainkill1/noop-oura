package com.noop.ble

import com.noop.oura.OuraRawSnapshot

// Android source adapter; Apple CLI consumes the independent pure capture/numeric twin.
data class OuraRawState(val sourceDeviceId: String? = null, val sourceIncarnation: Long = 0,
    val poll: OuraRawPollSnapshot = OuraRawPollSnapshot(), val capture: OuraRawSnapshot = OuraRawSnapshot(), val measurementTimes: Map<Long, Long> = emptyMap())
