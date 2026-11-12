package com.mannervolt.vehicle

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 실제 차량 모드에서 GPS로 현재 위치를 받는다. 좌표는 기기 메모리에만 두고,
 * 충전 구역 안/밖 판단(Geo.isOutside) 결과만 서버로 보낸다 (FR-4.4, NFR-2).
 */
class ZoneMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val _location = MutableStateFlow<Pair<Double, Double>?>(null)
    val location: StateFlow<Pair<Double, Double>?> = _location.asStateFlow()
    private var listening = false

    private val listener = LocationListener { loc: Location -> _location.value = loc.latitude to loc.longitude }

    fun hasPermission() =
        appContext.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun start() {
        if (listening || !hasPermission()) return
        runCatching {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { _location.value = it.latitude to it.longitude }
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2_000L, 1f, listener, Looper.getMainLooper())
            listening = true
        }
    }

    fun stop() {
        if (!listening) return
        runCatching { lm.removeUpdates(listener) }
        listening = false
    }
}
