package com.mannervolt.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.EvChargePortOpenListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Pleos Vehicle SDK 2.3.2 연동.
 * initialize() → get(초기값) + register(변화 구독) → unregister → release()
 * - EvBattery: 배터리 SoC, 충전구 열림(충전기 연결/분리 판단), 충전 스위치
 * - Gear: P 이외 기어 = 이동 시작 신호
 * SDK 콜백은 SDK 스레드에서 오므로 StateFlow.update 로만 갱신한다.
 */
@Suppress("DEPRECATION")
class PleosVehicleDataSource(context: Context) : VehicleDataSource {
    private val appContext = context.applicationContext
    private var vehicle: Vehicle? = null

    private val _readings = MutableStateFlow(PleosReadings())
    val readings: StateFlow<PleosReadings> = _readings.asStateFlow()

    private val onError: (Exception) -> Unit = { e ->
        Log.w(TAG, "Vehicle SDK error: ${e.javaClass.simpleName}")
        _readings.update { it.copy(lastError = e.message ?: e.javaClass.simpleName) }
    }

    private fun ok() = _readings.update { it.copy(connected = true) }

    private val socListener = object : EvBatteryStateOfChargeListener {
        override fun onEvBatteryStateOfChargeUpdated(value: Int?) { ok(); _readings.update { it.copy(socPercent = value) } }
        override fun onFailed(e: Exception) = onError(e)
    }
    private val portListener = object : EvChargePortOpenListener {
        override fun onEvChargePortOpenUpdated(isOpened: Boolean?) { ok(); _readings.update { it.copy(chargePortOpen = isOpened) } }
        override fun onFailed(e: Exception) = onError(e)
    }
    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) { ok(); _readings.update { it.copy(gear = gear.label()) } }
        override fun onFailed(e: Exception) = onError(e)
    }

    override fun start() {
        if (vehicle != null) return
        val v = try {
            Vehicle(appContext).also { it.initialize() }
        } catch (t: Throwable) {
            Log.w(TAG, "Vehicle initialize failed: ${t.javaClass.simpleName}")
            _readings.update { it.copy(connected = false, lastError = t.message ?: "initialize failed") }
            return
        }
        vehicle = v
        runCatching {
            val ev = v.getEvBattery()
            ev.getEvBatteryStateOfCharge({ soc -> ok(); _readings.update { it.copy(socPercent = soc) } }, onError)
            ev.registerEvBatteryStateOfCharge(socListener)
            ev.isEvChargePortOpen({ open -> ok(); _readings.update { it.copy(chargePortOpen = open) } }, onError)
            ev.registerEvChargePortOpen(portListener)
            ev.getEvChargeSwitchState({ on -> ok(); _readings.update { it.copy(chargeSwitchOn = on) } }, onError)
            v.getGear().getCurrentGearSelectionState({ g -> ok(); _readings.update { it.copy(gear = g.label()) } }, onError)
            v.getGear().registerGearSelection(gearListener)
        }.onFailure { t ->
            Log.w(TAG, "Vehicle setup failed: ${t.javaClass.simpleName}")
            _readings.update { it.copy(lastError = t.message) }
        }
    }

    /** 연결 결과가 일정 시간 안에 오지 않으면 미연결로 표시한다. */
    fun markDisconnectedIfUnknown() = _readings.update { if (it.connected == null) it.copy(connected = false) else it }

    override fun stop() {
        val v = vehicle ?: return
        runCatching {
            v.getEvBattery().unregisterEvBatteryStateOfCharge(socListener)
            v.getEvBattery().unregisterEvChargePortOpen(portListener)
            v.getGear().unregisterGearSelection(gearListener)
        }
        runCatching { v.release() }
        vehicle = null
    }

    private fun VehicleGear?.label(): String? = when (this) {
        null, VehicleGear.GEAR_UNKNOWN -> null
        VehicleGear.GEAR_PARK -> "P"
        VehicleGear.GEAR_REVERSE -> "R"
        VehicleGear.GEAR_NEUTRAL -> "N"
        else -> "D"
    }

    private companion object { const val TAG = "PleosVehicle" }
}
