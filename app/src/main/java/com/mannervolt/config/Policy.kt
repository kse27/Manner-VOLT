package com.mannervolt.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 매너볼트 정책 값 (FR-5.1: 모든 값은 설정 파일로 조정). 원본: config/policy.json */
@Serializable
data class Policy(
    val version: Int = 2,
    val defaultDelayMinutes: Int = 10,
    val delayHistoryWindow: Int = 10,
    val maxPersonalDelayMinutes: Int = 60,
    val recommendationRoundMinutes: Int = 5,
    val chargingEfficiency: Double = 0.9,
    val undergroundBaseTempC: Double = 15.0,
    val undergroundOutsideWeight: Double = 0.3,
    val fastChargeThresholdKw: Double = 50.0,
    val fastChargeTaperStartPercent: Int = 80,
    val fastChargeTaperFactor: Double = 0.5,
    val initialScore: Int = 100,
    val minScore: Int = 0,
    val maxScore: Int = 150,
    val onTimeToleranceMinutes: Int = 5,
    val onTimeBonus: Int = 2,
    val latePenaltyStepMinutes: Int = 10,
    val latePenaltyPerStep: Int = 3,
    val maxLatePenalty: Int = 20,
    val waitlistMax: Int = 3,
    val preNotifyMinutes: Int = 15,
    val graceMinutes: Int = 15,
    val maxDepartureChangesPerSession: Int = 5,
    val emptyReportsToOverride: Int = 2,
    val zoneRadiusMeters: Double = 30.0,
    /** FR-12.2 출차 예정 상한: 예상 완충 + N분 */
    val maxDepartureBufferMinutes: Int = 30,
    /** FR-12.3 연장 */
    val maxExtensions: Int = 2,
    val maxExtensionMinutes: Int = 30,
    /** FR-12.2 법정 충전 방해 기준 (급속 1시간, 완속 14시간. 법령 원문 재확인 필요) */
    val legalFastLimitMinutes: Int = 60,
    val legalSlowLimitMinutes: Int = 840,
    /** FR-8 충전기 자동 매칭: 꽂은 시각 ± N분 */
    val matchWindowMinutes: Int = 5,
    /** FR-9 실제 충전 속도로 다시 계산 */
    val refineAfterMinutes: Int = 4,
    val refineThresholdMinutes: Int = 5,
    /** FR-12.4 대기 1순위 자리 유지: 도착 예정(없으면 빈 시각) + N분 */
    val queueHoldMinutes: Int = 10,
    /** FR-14 매너볼트를 쓰지 않는 차량의 완료 추정에 쓰는 충전량 */
    val assumedSessionKwh: Double = 30.0,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): Policy = json.decodeFromString(serializer(), text)
    }
}
