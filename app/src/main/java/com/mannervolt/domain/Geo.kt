package com.mannervolt.domain

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** 기기 안에서만 쓰는 위치 계산. 결과(구역 안/밖)만 서버로 보낸다 (NFR-2). */
object Geo {
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    fun isOutside(lat: Double, lon: Double, centerLat: Double, centerLon: Double, radiusMeters: Double): Boolean =
        distanceMeters(lat, lon, centerLat, centerLon) > radiusMeters
}
