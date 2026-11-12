package com.mannervolt.domain

import com.mannervolt.config.Policy
import kotlin.math.ceil

/** 약속한 출차 시각과 실제 출차 시각의 차이로 매너볼트를 계산한다 (FR-5.1, 5.2). */
object MannerScore {

    /** 점수 변화량. lateMinutes = 실제 - 약속 (음수면 일찍 출차). 연장한 충전은 약속을 지켜도 보너스가 없다 (FR-12.3). */
    fun delta(lateMinutes: Long, policy: Policy, extended: Boolean = false): Int {
        if (lateMinutes <= policy.onTimeToleranceMinutes) return if (extended) 0 else policy.onTimeBonus
        val over = (lateMinutes - policy.onTimeToleranceMinutes).toDouble()
        val steps = ceil(over / policy.latePenaltyStepMinutes).toInt()
        return -minOf(policy.maxLatePenalty, steps * policy.latePenaltyPerStep)
    }

    fun apply(score: Int, promisedAtMillis: Long, actualAtMillis: Long, policy: Policy, extended: Boolean = false): Int {
        val late = Math.floorDiv(actualAtMillis - promisedAtMillis, MINUTE)
        return (score + delta(late, policy, extended)).coerceIn(policy.minScore, policy.maxScore)
    }
}
