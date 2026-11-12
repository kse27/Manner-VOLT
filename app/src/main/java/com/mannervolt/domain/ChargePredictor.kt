package com.mannervolt.domain

import com.mannervolt.config.Policy
import kotlin.math.ceil
import kotlin.math.max

/** 충전 완료 예측 입력 (FR-1.1). */
data class ChargeInput(
    val startAtMillis: Long,
    val batteryCapacityKwh: Double,
    val currentPercent: Int,
    val targetPercent: Int,
    val chargerPowerKw: Double,
    val outsideTempC: Double,
    val underground: Boolean,
)

/** 배터리, 충전 출력, 외기 온도, 지하/실외 구분으로 충전 완료 시각을 계산하는 순수 함수. */
object ChargePredictor {

    fun effectiveTemperature(input: ChargeInput, policy: Policy): Double =
        if (input.underground) {
            policy.undergroundOutsideWeight * input.outsideTempC +
                (1 - policy.undergroundOutsideWeight) * policy.undergroundBaseTempC
        } else input.outsideTempC

    fun temperatureFactor(tempC: Double): Double = when {
        tempC < 0 -> 0.75
        tempC < 10 -> 0.85
        tempC <= 30 -> 1.0
        else -> 0.95
    }

    /** 필요한 충전 시간(분, 올림). */
    fun minutesToComplete(input: ChargeInput, policy: Policy): Long {
        val from = input.currentPercent.coerceIn(0, 100)
        val to = input.targetPercent.coerceIn(0, 100)
        if (from >= to || input.chargerPowerKw <= 0 || input.batteryCapacityKwh <= 0) return 0
        val power = input.chargerPowerKw * policy.chargingEfficiency *
            temperatureFactor(effectiveTemperature(input, policy))
        val kwhPerPercent = input.batteryCapacityKwh / 100.0
        val hours = if (input.chargerPowerKw >= policy.fastChargeThresholdKw) {
            val taperStart = policy.fastChargeTaperStartPercent
            val normalPercent = max(0, minOf(to, taperStart) - from)
            val taperPercent = max(0, to - max(from, taperStart))
            normalPercent * kwhPerPercent / power +
                taperPercent * kwhPerPercent / (power * policy.fastChargeTaperFactor)
        } else {
            (to - from) * kwhPerPercent / power
        }
        // 부동소수 오차로 정확히 떨어지는 값이 1분 늘어나지 않도록 작은 값을 뺀 뒤 올림
        return ceil(hours * 60.0 - 1e-6).toLong().coerceAtLeast(0)
    }

    fun predictCompletion(input: ChargeInput, policy: Policy): Long =
        input.startAtMillis + minutesToComplete(input, policy) * MINUTE
}

const val MINUTE = 60_000L
