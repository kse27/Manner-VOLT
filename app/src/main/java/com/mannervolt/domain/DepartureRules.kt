package com.mannervolt.domain

import com.mannervolt.config.Policy
import kotlin.math.ceil

/** 출차 시각을 고를 수 있는 범위와 변경 규칙 (FR-12.2, 12.3). 앱과 서버가 같은 규칙을 쓴다. */
object DepartureRules {

    fun isFast(powerKw: Double, policy: Policy) = powerKw >= policy.fastChargeThresholdKw

    /** 법정 충전 방해 기준 (분): 급속 1시간, 완속 14시간. */
    fun legalLimitMinutes(powerKw: Double, policy: Policy): Int =
        if (isFast(powerKw, policy)) policy.legalFastLimitMinutes else policy.legalSlowLimitMinutes

    /** 고를 수 있는 가장 늦은 출차 시각 = min(완충 + 30분, 시작 + 법정 기준). 완충보다 이르지는 않다. */
    fun maxDeparture(startAt: Long, completionAt: Long, powerKw: Double, policy: Policy): Long {
        val buffer = completionAt + policy.maxDepartureBufferMinutes * MINUTE
        val legal = startAt + legalLimitMinutes(powerKw, policy) * MINUTE
        return maxOf(completionAt, minOf(buffer, legal))
    }

    /** 법정 기준 때문에 상한이 줄어들었는지 (화면 안내용). */
    fun limitedByLaw(startAt: Long, completionAt: Long, powerKw: Double, policy: Policy): Boolean =
        startAt + legalLimitMinutes(powerKw, policy) * MINUTE < completionAt + policy.maxDepartureBufferMinutes * MINUTE

    fun clamp(selected: Long, startAt: Long, completionAt: Long, powerKw: Double, policy: Policy): Long =
        selected.coerceIn(completionAt, maxDeparture(startAt, completionAt, powerKw, policy))

    /** 확정 후 출차 시각 변경 검사. null 이면 허용, 아니면 사용자에게 보여 줄 거절 사유. */
    fun checkChange(
        before: Long, after: Long, now: Long, extensionsUsed: Int, recalc: Boolean, policy: Policy,
    ): String? {
        if (after <= before || recalc) return null // 앞당기기와 충전 속도 변화에 따른 변경은 연장이 아니다
        return when {
            now >= before -> "출차 예정 시각이 지나서 연장할 수 없어요."
            extensionsUsed >= policy.maxExtensions -> "연장은 최대 ${policy.maxExtensions}번까지 할 수 있어요."
            after - before > policy.maxExtensionMinutes * MINUTE -> "한 번에 최대 ${policy.maxExtensionMinutes}분까지 연장할 수 있어요."
            else -> null
        }
    }

    fun isExtension(before: Long, after: Long, recalc: Boolean) = !recalc && after > before
}

/** 충전 완료 시각 보정 (FR-9): 꽂은 직후에는 출력 기준 추정, 몇 분 뒤 실제 배터리 상승 속도로 다시 계산. */
object ChargeRefiner {

    /** 실제 상승 속도로 계산한 완료 시각. 아직 판단할 수 없으면 null. */
    fun refine(startAt: Long, startPercent: Double, now: Long, nowPercent: Double, targetPercent: Int, policy: Policy): Long? {
        val elapsed = now - startAt
        val gained = nowPercent - startPercent
        if (elapsed < policy.refineAfterMinutes * MINUTE || gained < 1.0) return null
        if (nowPercent >= targetPercent) return now
        val msPerPercent = elapsed / gained
        return now + ceil((targetPercent - nowPercent) * msPerPercent / MINUTE).toLong() * MINUTE
    }

    fun changedEnough(before: Long, after: Long, policy: Policy) =
        kotlin.math.abs(after - before) >= policy.refineThresholdMinutes * MINUTE

    /** 매너볼트를 쓰지 않는 차량의 완료 추정 (FR-14): 공공데이터 충전 시작 + 가정 충전량 ÷ 출력. */
    fun estimateForUnshared(startedAt: Long, powerKw: Double, policy: Policy): Long {
        if (powerKw <= 0) return startedAt
        val hours = policy.assumedSessionKwh / (powerKw * policy.chargingEfficiency)
        return startedAt + ceil(hours * 60).toLong() * MINUTE
    }
}
