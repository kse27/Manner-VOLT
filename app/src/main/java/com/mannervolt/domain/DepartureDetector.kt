package com.mannervolt.domain

import kotlinx.serialization.Serializable

@Serializable
enum class DepartureVerdict { PARKED, SUSPECT, CONFIRMED }

/** 출차 판단 신호. 위치는 원 좌표가 아니라 구역 이탈 여부만 담는다 (FR-4.4). */
data class DepartureSignals(
    val chargingEnded: Boolean,
    val unplugged: Boolean,
    val leftZone: Boolean,
    /** 기어 D 또는 R */
    val gearMoving: Boolean = false,
)

/** 충전기 분리(충전 종료) + 기어 D/R + 충전 구역 이탈을 모두 만족해야 출차로 확정한다 (FR-12.1). */
object DepartureDetector {
    fun evaluate(s: DepartureSignals): DepartureVerdict = when {
        s.unplugged && s.gearMoving && s.leftZone -> DepartureVerdict.CONFIRMED
        (s.unplugged && s.gearMoving) || s.leftZone -> DepartureVerdict.SUSPECT
        else -> DepartureVerdict.PARKED
    }

    fun gearMoving(gear: String?) = gear == "D" || gear == "R"
}
