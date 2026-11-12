package com.mannervolt

import com.mannervolt.backend.ArrivalCheck
import com.mannervolt.backend.BackendException
import com.mannervolt.backend.ChangeReason
import com.mannervolt.backend.ChargerStatus
import com.mannervolt.backend.DepartureReport
import com.mannervolt.backend.EventType
import com.mannervolt.backend.LocalServer
import com.mannervolt.backend.Observation
import com.mannervolt.backend.SampleData
import com.mannervolt.backend.SessionRequest
import com.mannervolt.config.Policy
import com.mannervolt.domain.DepartureVerdict
import com.mannervolt.domain.MINUTE
import com.mannervolt.sim.Clock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** 기획서 4항 시나리오 ①~④ 예시 기반 고정 테스트 */
class ScenarioTest {
    private class FakeClock(var t: Long) : Clock { override fun now() = t }

    private val day = 1_800_000_000_000L - Math.floorMod(1_800_000_000_000L, 24 * 60 * MINUTE)
    private fun at(h: Int, m: Int) = day + (h * 60 + m) * MINUTE
    private val key = SampleData.SCENARIO_CHARGER

    @Test fun fullScenario() = runBlocking<Unit> {
        val clock = FakeClock(at(19, 0))
        val server = LocalServer(Policy(), clock, day)
        // ① A가 꽂음 → 같은 시각에 '충전 중'이 된 3번을 찾아 확인 → 23:00 완료 / 23:10 출차 확정
        server.simPhysicalPlug(key, at(19, 0))
        assertEquals(listOf(key), server.match("A", SampleData.HOME_STATION_ID, at(19, 0)).candidates.map { it.chargerKey })
        server.upsertSession("A", SessionRequest(key, at(19, 0), at(23, 0), at(23, 10)))
        // ② 22:30 B가 배치도에서 확인 후 대기 등록
        clock.t = at(22, 30)
        val tile = server.board("B", SampleData.HOME_STATION_ID).chargers.first { it.chargerKey == key }
        assertEquals(ChargerStatus.IN_USE, tile.status)
        assertEquals(at(23, 10), tile.plannedDepartureAt)
        assertEquals(100, tile.ownerScore) // 배치도에는 차량 번호 없이 매너볼트만 (새 계정 100V)
        server.joinQueue("B", key, at(22, 58))
        // 22:55 사전 알림 (15분 전)
        clock.t = at(22, 55); server.tick()
        assertTrue(server.events("B", 0).any { it.type == EventType.PRE_DEPARTURE })
        // ③ 23:00 충전 완료 안내
        clock.t = at(23, 0); server.tick()
        assertTrue(server.events("A", 0).any { it.type == EventType.CHARGE_COMPLETE })
        // 23:08 분리만 → 주차 중, 분리 + 기어 D(구역 안) → 이동 감지, 분리 + 기어 D + 구역 이탈 → 확정
        clock.t = at(23, 8)
        assertEquals(DepartureVerdict.PARKED, server.reportDeparture("A", DepartureReport(key, true, true, false, clock.t)).verdict)
        assertEquals(DepartureVerdict.SUSPECT, server.reportDeparture("A", DepartureReport(key, true, true, false, clock.t, gearMoving = true)).verdict)
        val r = server.reportDeparture("A", DepartureReport(key, true, true, true, clock.t, gearMoving = true))
        assertEquals(DepartureVerdict.CONFIRMED, r.verdict)
        assertEquals(102, r.scoreAfter) // 100 + 2 (약속 준수)
        assertTrue(server.events("B", 0).any { it.type == EventType.CHARGER_AVAILABLE })
        // B 도착 확인
        server.arrivalCheck("B", ArrivalCheck(key, Observation.EMPTY))
        assertTrue(server.events("B", 0).any { it.type == EventType.ARRIVAL_CONFIRMED })
        // ④ 기록이 쌓여 다음 추천에 쓰인다
        assertEquals(1, server.me("A").history.size)
        assertTrue(server.auditLog().any { it.action == "SCORE" && it.before == "100" && it.after == "102" })
    }

    @Test fun graceExpiredSuggestsAlternatives() = runBlocking<Unit> {
        val clock = FakeClock(at(19, 0))
        val server = LocalServer(Policy(), clock, day)
        server.upsertSession("A", SessionRequest(key, at(19, 0), at(23, 0), at(23, 10)))
        server.joinQueue("B", key)
        clock.t = at(23, 26); server.tick()
        val e = server.events("B", 0).first { it.type == EventType.GRACE_EXPIRED }
        assertTrue(e.alternatives.isNotEmpty())
    }

    @Test fun onlyOwnerCanReportDeparture() = runBlocking<Unit> {
        val clock = FakeClock(at(19, 0))
        val server = LocalServer(Policy(), clock, day)
        server.upsertSession("A", SessionRequest(key, at(19, 0), at(23, 0), at(23, 10)))
        try {
            server.reportDeparture("B", DepartureReport(key, true, true, true, clock.t, gearMoving = true)); fail()
        } catch (e: BackendException) { assertEquals(403, e.code) }
    }

    @Test fun singleEmptyReportDoesNotFreeCharger() = runBlocking<Unit> {
        val clock = FakeClock(at(23, 30))
        val server = LocalServer(Policy(), clock, day)
        server.upsertSession("A", SessionRequest(key, at(19, 0), at(23, 0), at(23, 10)))
        server.arrivalCheck("B", ArrivalCheck(key, Observation.EMPTY))
        assertEquals(ChargerStatus.IN_USE, server.board("B", SampleData.HOME_STATION_ID).chargers.first { it.chargerKey == key }.status)
        server.arrivalCheck("C", ArrivalCheck(key, Observation.EMPTY))
        assertEquals(ChargerStatus.AVAILABLE, server.board("B", SampleData.HOME_STATION_ID).chargers.first { it.chargerKey == key }.status)
    }

    private fun board(server: LocalServer, me: String) = runBlocking { server.board(me, SampleData.HOME_STATION_ID).chargers.associateBy { it.chargerKey } }

    /** FR-8: 꽂은 시각 ± 5분 안에 '충전 중'이 된, 약속 없는 충전기만 후보 */
    @Test fun matchUsesPlugTimeWindow() = runBlocking<Unit> {
        val clock = FakeClock(at(19, 0))
        val server = LocalServer(Policy(), clock, day)
        server.simPhysicalPlug(key, at(19, 0))
        assertEquals(listOf(key), server.match("A", SampleData.HOME_STATION_ID, at(19, 4)).candidates.map { it.chargerKey })
        assertTrue(server.match("A", SampleData.HOME_STATION_ID, at(19, 6)).candidates.isEmpty())
        // 약속을 공유한 충전기(01)는 후보가 아니다
        assertTrue(server.match("A", SampleData.HOME_STATION_ID, at(18, 40)).candidates.none { it.chargerKey.endsWith("-01") })
    }

    /** FR-12.2: 완충 + 30분을 넘는 출차 예정은 거절 */
    @Test fun departureCapEnforced() = runBlocking<Unit> {
        val server = LocalServer(Policy(), FakeClock(at(19, 0)), day)
        try {
            server.upsertSession("A", SessionRequest(key, at(19, 0), at(23, 0), at(23, 31))); fail()
        } catch (e: BackendException) { assertEquals(400, e.code) }
        server.upsertSession("A", SessionRequest(key, at(19, 0), at(23, 0), at(23, 30)))
    }

    /** FR-12.3: 연장은 확정 시각 전 최대 2회, 대기자에게 알림, 연장한 충전은 보너스 없음 */
    @Test fun extensionRulesAndNoBonus() = runBlocking<Unit> {
        val clock = FakeClock(at(19, 0))
        val server = LocalServer(Policy(), clock, day)
        server.upsertSession("A", SessionRequest(key, at(19, 0), at(23, 0), at(23, 0)))
        server.joinQueue("B", key)
        clock.t = at(22, 0)
        server.upsertSession("A", SessionRequest(key, at(19, 0), at(23, 0), at(23, 10)))
        assertTrue(server.events("B", 0).any { it.type == EventType.DEPARTURE_CHANGED && it.title.contains("연장") })
        server.upsertSession("A", SessionRequest(key, at(19, 0), at(23, 0), at(23, 20)))
        assertEquals(2, board(server, "A").getValue(key).extensionsUsed)
        try {
            server.upsertSession("A", SessionRequest(key, at(19, 0), at(23, 0), at(23, 30))); fail()
        } catch (e: BackendException) { assertEquals(409, e.code) }
        // 약속(23:20) 안에 출차해도 연장했으므로 보너스 없음
        clock.t = at(23, 18)
        val r = server.reportDeparture("A", DepartureReport(key, true, true, true, clock.t, gearMoving = true))
        assertEquals(r.scoreBefore, r.scoreAfter)
    }

    @Test fun cannotExtendAfterPlannedTime() = runBlocking<Unit> {
        val clock = FakeClock(at(19, 0))
        val server = LocalServer(Policy(), clock, day)
        server.upsertSession("A", SessionRequest(key, at(19, 0), at(23, 0), at(23, 10)))
        clock.t = at(23, 11)
        try {
            server.upsertSession("A", SessionRequest(key, at(19, 0), at(23, 0), at(23, 20))); fail()
        } catch (e: BackendException) { assertEquals(409, e.code) }
    }

    /** FR-9.3: 충전 속도 변화에 따른 변경은 연장으로 세지 않는다 */
    @Test fun recalcIsNotAnExtension() = runBlocking<Unit> {
        val clock = FakeClock(at(19, 20))
        val server = LocalServer(Policy(), clock, day)
        server.upsertSession("A", SessionRequest(key, at(19, 0), at(23, 0), at(23, 10)))
        server.upsertSession("A", SessionRequest(key, at(19, 0), at(24, 43), at(24, 55), ChangeReason.RECALC))
        val v = board(server, "A").getValue(key)
        assertEquals(0, v.extensionsUsed)
        assertEquals(at(24, 55), v.plannedDepartureAt)
    }

    /** FR-12.4: 빈 충전기의 1순위가 도착 예정 + 10분 안에 안 오면 자동 취소, 다음 순번에게 알림 */
    @Test fun queueHoldExpiresAndPromotesNext() = runBlocking<Unit> {
        val clock = FakeClock(at(19, 0))
        val server = LocalServer(Policy(), clock, day)
        server.upsertSession("A", SessionRequest(key, at(19, 0), at(23, 0), at(23, 10)))
        server.joinQueue("B", key, at(23, 0))
        clock.t = at(22, 40); server.joinQueue("C", key, at(23, 30))
        clock.t = at(23, 8)
        server.reportDeparture("A", DepartureReport(key, true, true, true, clock.t, gearMoving = true))
        clock.t = at(23, 18); server.tick()
        assertTrue(server.events("B", 0).none { it.type == EventType.QUEUE_CANCELLED })
        clock.t = at(23, 19); server.tick()
        assertTrue(server.events("B", 0).any { it.type == EventType.QUEUE_CANCELLED })
        assertTrue(server.events("C", 0).any { it.type == EventType.CHARGER_AVAILABLE && it.body.contains("1순위") })
        assertEquals(1, board(server, "C").getValue(key).myQueuePosition)
    }

    /** FR-13.1 / FR-14: 약속 공유 차량은 매너볼트, 미사용 차량은 공공데이터 추정만 */
    @Test fun sharedShowsScoreUnsharedShowsEstimate() = runBlocking<Unit> {
        val server = LocalServer(Policy(), FakeClock(at(19, 0)), day)
        val b = board(server, "B")
        val shared = b.getValue("${SampleData.HOME_STATION_ID}-01")
        val unshared = b.getValue("${SampleData.HOME_STATION_ID}-02")
        assertEquals(112, shared.ownerScore)
        assertEquals(null, shared.estimatedCompletionAt)
        assertEquals(ChargerStatus.IN_USE, unshared.status)
        assertEquals(null, unshared.ownerScore)
        assertTrue(unshared.estimatedCompletionAt!! > unshared.chargingStartedAt!!)
    }

    @Test fun backendPolicyMatchesAppPolicy() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "config/policy.json").exists() }
        val app = Policy.parse(File(root, "config/policy.json").readText())
        val backend = Policy.parse(File(root, "backend/src/policy.json").readText())
        assertEquals(app, backend)
    }
}
