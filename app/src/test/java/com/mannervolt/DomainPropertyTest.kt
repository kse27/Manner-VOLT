package com.mannervolt

import com.mannervolt.config.Policy
import com.mannervolt.domain.ChargeInput
import com.mannervolt.domain.ChargePredictor
import com.mannervolt.domain.DelaySample
import com.mannervolt.domain.DepartureDetector
import com.mannervolt.domain.ChargeRefiner
import com.mannervolt.domain.DepartureRecommender
import com.mannervolt.domain.DepartureRules
import com.mannervolt.domain.DepartureSignals
import com.mannervolt.domain.DepartureVerdict
import com.mannervolt.domain.MINUTE
import com.mannervolt.domain.MannerScore
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PBT-03: 불변 성질 테스트. 실패하면 Kotest가 시드와 축소된 입력을 출력한다 (PBT-08).
 * 재현: PropTestConfig(seed = <출력된 시드>) 를 checkAll 첫 인자로 넣는다.
 */
class DomainPropertyTest {
    private val policy = Policy()
    private val cfg = PropTestConfig(iterations = 500)

    @Test fun completionNeverBeforeStart() = runBlocking<Unit> {
        checkAll(cfg, Gen.chargeInput) { input ->
            assertTrue(ChargePredictor.predictCompletion(input, policy) >= input.startAtMillis)
        }
    }

    @Test fun higherCurrentPercentNeverTakesLonger() = runBlocking<Unit> {
        checkAll(cfg, Gen.chargeInput, Gen.percent) { input, other ->
            val lo = minOf(input.currentPercent, other)
            val hi = maxOf(input.currentPercent, other)
            val tLo = ChargePredictor.minutesToComplete(input.copy(currentPercent = lo), policy)
            val tHi = ChargePredictor.minutesToComplete(input.copy(currentPercent = hi), policy)
            assertTrue("lo=$lo hi=$hi", tHi <= tLo)
        }
    }

    @Test fun morePowerWithinSameClassNeverSlower() = runBlocking<Unit> {
        checkAll(cfg, Gen.chargeInput, Gen.power) { input, other ->
            // 급속/완속 경계(테이퍼 적용 여부)를 넘지 않는 같은 등급 안에서 비교
            val fast = input.chargerPowerKw >= policy.fastChargeThresholdKw
            if (fast != (other >= policy.fastChargeThresholdKw)) return@checkAll
            val lo = minOf(input.chargerPowerKw, other)
            val hi = maxOf(input.chargerPowerKw, other)
            assertTrue(
                ChargePredictor.minutesToComplete(input.copy(chargerPowerKw = hi), policy) <=
                    ChargePredictor.minutesToComplete(input.copy(chargerPowerKw = lo), policy),
            )
        }
    }

    @Test fun recommendationIsAfterCompletionAndRounded() = runBlocking<Unit> {
        val sample = Arb.list(
            io.kotest.property.arbitrary.arbitrary {
                val c = Gen.instant.bind()
                DelaySample(c, c + Arb.long(-30L..180L).bind() * MINUTE)
            },
            0..15,
        )
        checkAll(cfg, Gen.instant, sample) { completion, history ->
            val rec = DepartureRecommender.recommend(completion, history, policy)
            assertTrue(rec >= completion)
            assertEquals(0L, Math.floorMod(rec, policy.recommendationRoundMinutes * MINUTE))
            assertTrue(rec - completion < (policy.maxPersonalDelayMinutes + policy.recommendationRoundMinutes) * MINUTE)
        }
    }

    @Test fun scoreStaysInRange() = runBlocking<Unit> {
        checkAll(cfg, Arb.int(policy.minScore..policy.maxScore), Gen.instant, Arb.long(-600L..1_440L)) { score, promised, lateMin ->
            val next = MannerScore.apply(score, promised, promised + lateMin * MINUTE, policy)
            assertTrue(next in policy.minScore..policy.maxScore)
        }
    }

    @Test fun laterDepartureNeverScoresBetter() = runBlocking<Unit> {
        checkAll(cfg, Arb.long(-600L..1_440L), Arb.long(-600L..1_440L)) { a, b ->
            val early = minOf(a, b); val late = maxOf(a, b)
            assertTrue(MannerScore.delta(late, policy) <= MannerScore.delta(early, policy))
        }
    }

    /** FR-12.1: 충전기 분리 + 기어 D/R + 구역 이탈이 모두 있어야만 출차 확정 */
    @Test fun confirmedRequiresUnplugGearAndLeavingZone() = runBlocking<Unit> {
        checkAll(Arb.boolean(), Arb.boolean(), Arb.boolean(), Arb.boolean()) { ended, unplugged, left, moving ->
            val v = DepartureDetector.evaluate(DepartureSignals(ended, unplugged, left, moving))
            assertEquals(unplugged && left && moving, v == DepartureVerdict.CONFIRMED)
        }
    }

    /** FR-12.2: 고를 수 있는 출차 상한은 완충 이후, 완충 + 30분 이내, 법정 기준(완충이 그보다 늦으면 완충) 이내 */
    @Test fun maxDepartureWithinBufferAndLaw() = runBlocking<Unit> {
        checkAll(cfg, Gen.instant, Arb.long(0L..20 * 60L), Gen.power) { start, minutes, power ->
            val completion = start + minutes * MINUTE
            val cap = DepartureRules.maxDeparture(start, completion, power, policy)
            val legal = start + DepartureRules.legalLimitMinutes(power, policy) * MINUTE
            assertTrue(cap >= completion)
            assertTrue(cap <= completion + policy.maxDepartureBufferMinutes * MINUTE)
            assertTrue(cap <= maxOf(completion, legal))
        }
    }

    /** FR-12.3: 연장은 확정 시각 전, 최대 횟수, 한 번에 최대 분 안에서만. 앞당기기와 충전 속도 변경은 항상 허용 */
    @Test fun extensionRules() = runBlocking<Unit> {
        checkAll(cfg, Gen.instant, Arb.long(-60L..60L), Arb.long(-90L..90L), Arb.int(0..4)) { before, nowOffset, delta, used ->
            val now = before + nowOffset * MINUTE
            val after = before + delta * MINUTE
            val err = DepartureRules.checkChange(before, after, now, used, false, policy)
            val allowed = delta <= 0 || (nowOffset < 0 && used < policy.maxExtensions && delta <= policy.maxExtensionMinutes)
            assertEquals(allowed, err == null)
            assertEquals(null, DepartureRules.checkChange(before, after, now, used, true, policy))
        }
    }

    /** FR-12.3: 연장한 충전은 약속을 지켜도 보너스가 없고, 늦으면 같은 벌점 */
    @Test fun extendedNeverScoresMoreThanNormal() = runBlocking<Unit> {
        checkAll(cfg, Arb.long(-600L..1_440L)) { late ->
            val ext = MannerScore.delta(late, policy, extended = true)
            assertTrue(ext <= MannerScore.delta(late, policy))
            assertTrue(ext <= 0)
        }
    }

    /** FR-9: 다시 계산한 완료 시각은 현재 이후이며, 같은 시간에 더 많이 오르면 더 빨리 끝난다 */
    @Test fun refinedCompletionIsMonotonic() = runBlocking<Unit> {
        checkAll(cfg, Gen.instant, Arb.double(0.0..70.0), Arb.long(4L..120L), Arb.double(1.0..20.0), Arb.double(0.0..10.0)) { start, soc, mins, gained, more ->
            val now = start + mins * MINUTE
            val target = 80
            val slow = ChargeRefiner.refine(start, soc, now, soc + gained, target, policy)!!
            val fast = ChargeRefiner.refine(start, soc, now, soc + gained + more, target, policy)!!
            assertTrue(slow >= now && fast >= now)
            assertTrue(fast <= slow)
        }
    }

    @Test fun refineWaitsForEnoughData() {
        val start = 1_800_000_000_000L
        assertEquals(null, ChargeRefiner.refine(start, 40.0, start + 3 * MINUTE, 45.0, 80, policy)) // 4분 전
        assertEquals(null, ChargeRefiner.refine(start, 40.0, start + 10 * MINUTE, 40.5, 80, policy)) // 1%p 미만
    }

    // ---- 기획서 4항 예시 고정 ----

    @Test fun scenarioA_completes2300_recommends2310() {
        val start = 1_800_000_000_000L - Math.floorMod(1_800_000_000_000L, 60 * MINUTE) // 정시
        val input = ChargeInput(start, 63.0, 40, 80, 7.0, 8.0, underground = true)
        val completion = ChargePredictor.predictCompletion(input, policy)
        assertEquals(240L, (completion - start) / MINUTE) // 19:00 → 23:00
        val history = listOf(8L, 12L, 10L).map { DelaySample(0, it * MINUTE) }
        assertEquals(completion + 10 * MINUTE, DepartureRecommender.recommend(completion, history, policy)) // 23:10
    }

    @Test fun noHistoryUsesDefaultDelay() {
        assertEquals(policy.defaultDelayMinutes, DepartureRecommender.personalDelayMinutes(emptyList(), policy))
    }

    @Test fun capExamples() {
        val start = 1_800_000_000_000L - Math.floorMod(1_800_000_000_000L, 60 * MINUTE)
        // 완속: 23:00 완충 → 최대 23:30
        assertEquals(start + 270 * MINUTE, DepartureRules.maxDeparture(start, start + 240 * MINUTE, 7.0, policy))
        // 급속: 50분 완충이어도 법정 1시간까지만
        assertEquals(start + 60 * MINUTE, DepartureRules.maxDeparture(start, start + 50 * MINUTE, 100.0, policy))
        assertTrue(DepartureRules.limitedByLaw(start, start + 50 * MINUTE, 100.0, policy))
    }

    @Test fun scoreExamples() {
        assertEquals(+2, MannerScore.delta(-2, policy))   // 23:08 출차, 약속 23:10
        assertEquals(+2, MannerScore.delta(5, policy))
        assertEquals(-3, MannerScore.delta(6, policy))
        assertEquals(-6, MannerScore.delta(25, policy))
        assertEquals(-20, MannerScore.delta(600, policy))
    }
}
