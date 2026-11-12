package com.mannervolt.domain

import com.mannervolt.config.Policy

/** 과거 출차 기록 1건: 충전 완료 시각과 실제 출차 시각. */
data class DelaySample(val completionAtMillis: Long, val actualDepartureAtMillis: Long)

/** 충전 완료 예상 + 개인별 평균 지연으로 출차 예정 시각을 추천한다 (FR-1.2, 1.5, 5.4). */
object DepartureRecommender {

    fun personalDelayMinutes(history: List<DelaySample>, policy: Policy): Int {
        val recent = history.takeLast(policy.delayHistoryWindow)
        if (recent.isEmpty()) return policy.defaultDelayMinutes
        val avg = recent.map { (it.actualDepartureAtMillis - it.completionAtMillis) / MINUTE.toDouble() }.average()
        return Math.round(avg).toInt().coerceIn(0, policy.maxPersonalDelayMinutes)
    }

    fun recommend(completionAtMillis: Long, history: List<DelaySample>, policy: Policy): Long {
        val raw = completionAtMillis + personalDelayMinutes(history, policy) * MINUTE
        return roundUp(raw, policy.recommendationRoundMinutes)
    }

    /** 분 단위 배수로 올림 (초, 밀리초는 버린 뒤 올림 판단). */
    fun roundUp(millis: Long, stepMinutes: Int): Long {
        val step = stepMinutes.coerceAtLeast(1) * MINUTE
        val rem = Math.floorMod(millis, step)
        return if (rem == 0L) millis else millis - rem + step
    }
}
