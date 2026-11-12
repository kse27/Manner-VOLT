package com.mannervolt.vehicle

import com.mannervolt.config.Policy
import com.mannervolt.domain.ChargeInput
import com.mannervolt.domain.ChargePredictor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 가상 차량 (FR-6.2): 배터리, 충전 상태, 충전기 연결, GPS를 시뮬레이터에서 제어한다. */
class SimulatedVehicle(initial: VehicleSnapshot, private val policy: Policy) : VehicleDataSource {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<VehicleSnapshot> = _state.asStateFlow()

    override fun start() = Unit
    override fun stop() = Unit

    fun update(f: (VehicleSnapshot) -> VehicleSnapshot) = _state.update(f)

    fun plugIn() = _state.update { it.copy(plugged = true, charging = it.socPercent < it.targetPercent, gear = "P") }
    fun unplug() = _state.update { it.copy(plugged = false, charging = false) }
    fun moveTo(lat: Double, lon: Double, gear: String = "D") = _state.update { it.copy(lat = lat, lon = lon, gear = gear) }

    /** 시뮬레이션 시간 경과만큼 SoC를 올린다. 예측 모델과 같은 출력 계산을 써서 예측과 실제가 맞도록 한다. */
    fun advance(elapsedMillis: Long) {
        if (elapsedMillis <= 0) return
        _state.update { s ->
            if (!s.plugged || !s.charging) return@update s
            val oneHourInput = ChargeInput(0, s.batteryCapacityKwh, 0, 100, s.chargerPowerKw * s.powerFactor, s.outsideTempC, s.underground)
            val minutesFor100 = ChargePredictor.minutesToComplete(oneHourInput, policy).coerceAtLeast(1)
            val gained = elapsedMillis / 60_000.0 * 100.0 / minutesFor100
            val soc = (s.socPercent + gained).coerceAtMost(s.targetPercent.toDouble())
            s.copy(socPercent = soc, charging = soc < s.targetPercent)
        }
    }
}
