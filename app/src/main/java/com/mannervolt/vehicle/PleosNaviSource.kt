package com.mannervolt.vehicle

import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 내비 목적지까지 남은 시간. 목적지 이름은 화면 표시에만 쓰고 서버로 보내지 않는다. */
data class NaviRemaining(val destination: String, val remainSeconds: Int, val remainMeters: Int)

/**
 * Pleos NaviHelper 3.0.5: 기본 내비의 목적지 남은 시간을 읽는다 (FR-11).
 * 콜백이 오지 않을 수 있어 주기적으로 조회한다. 경로가 없으면 남은 거리가 0으로 오므로 null로 둔다.
 */
class PleosNaviSource(context: Context, private val scope: CoroutineScope) {
    private val helper = runCatching { NaviHelper(context.applicationContext) }.getOrNull()
    private val _remaining = MutableStateFlow<NaviRemaining?>(null)
    val remaining: StateFlow<NaviRemaining?> = _remaining.asStateFlow()
    private val _connected = MutableStateFlow<Boolean?>(null)
    val connected: StateFlow<Boolean?> = _connected.asStateFlow()
    private var poll: Job? = null
    private var initialized = false

    private val listener = object : NaviHelperEventListener {
        override fun onDestinationInfo(p0: DestinationInfo) = update(p0.name, p0.remainTime, p0.remainDistance)
        override fun onDrivingInfo(p0: DrivingInfo) = p0.destination.let { update(it.name, it.duration, it.distance) }
        override fun onRouteCancelled() { _remaining.value = null }
        override fun onRouteEnded() { _remaining.value = null }
    }

    private fun update(name: String?, seconds: Int, meters: Int) {
        _remaining.value = if (meters <= 0 || seconds <= 0) null else NaviRemaining(name.orEmpty(), seconds, meters)
    }

    fun start() {
        if (poll != null) return
        if (!initialized) {
            initialized = true
            runCatching {
                helper?.initialize(
                    { _connected.value = true; runCatching { helper?.addListener(listener) } },
                    { e -> Log.w(TAG, "NaviHelper init failed: ${e.javaClass.simpleName}"); _connected.value = false },
                ) ?: run { _connected.value = false }
            }.onFailure { _connected.value = false }
        }
        poll = scope.launch {
            while (isActive) {
                runCatching { helper?.getDestinationInfo() }
                delay(POLL_MS)
            }
        }
    }

    fun stop() { poll?.cancel(); poll = null }

    fun release() {
        stop()
        runCatching { helper?.removeListener(listener); helper?.release() }
    }

    private companion object {
        const val TAG = "MannerVoltNavi"
        const val POLL_MS = 10_000L
    }
}
