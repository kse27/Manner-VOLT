package com.mannervolt.backend

import com.mannervolt.config.Policy
import com.mannervolt.domain.ChargeRefiner
import com.mannervolt.domain.DepartureDetector
import com.mannervolt.domain.DepartureRules
import com.mannervolt.domain.DepartureSignals
import com.mannervolt.domain.DepartureVerdict
import com.mannervolt.domain.MINUTE
import com.mannervolt.domain.MannerScore
import com.mannervolt.sim.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

/**
 * 시연용 앱 내부 서버. AWS 백엔드(backend/src)와 같은 규칙을 적용한다.
 * 여러 익명 기기(운전자 A, B)가 한 상태를 공유하고, [LocalBackend]가 기기별 창구가 된다.
 *
 * 충전기 상태는 두 층이다.
 * - 공공데이터 층 [Charger.physicalStartedAt]: 실제로 차가 꽂혀 '충전 중'이 된 시각 (공공 API의 nowTsdt 역할). 시뮬레이터가 바꾼다.
 * - 매너볼트 층 [Charger.session]: 운전자가 확정한 출차 약속.
 */
class LocalServer(private val policy: Policy, private val clock: Clock, private val dayStart: Long) {

    private class Session(
        val ownerId: String,
        val startedAt: Long,
        var predictedCompletionAt: Long?,
        var plannedDepartureAt: Long?,
        val shared: Boolean,
        var changes: Int = 0,
        var extensions: Int = 0,
        var verdict: DepartureVerdict = DepartureVerdict.PARKED,
        var preNotified: Boolean = false,
        var completeNotified: Boolean = false,
        var graceNotified: Boolean = false,
    )

    private class QueueEntry(val deviceId: String, val at: Long, var etaAt: Long?)

    private class Charger(val stationId: String, val def: SampleData.ChargerDef) {
        val key = SampleData.key(stationId, def.chgerId)
        var outOfService = false
        var physicalStartedAt: Long? = null
        var session: Session? = null
        val queue = mutableListOf<QueueEntry>()
        var awaitingArrivalCheck = false
        /** 출차가 확인되어 빈 시각. 대기 1순위의 자리 유지 기준 (FR-12.4) */
        var availableAt: Long? = null
        val emptyReports = mutableSetOf<String>()
    }

    private class Account(var score: Int, val history: MutableList<HistoryEntry> = mutableListOf()) {
        val events = mutableListOf<AppEvent>()
    }

    data class AuditEntry(val at: Long, val actor: String, val action: String, val target: String, val before: String, val after: String)

    private val mutex = Mutex()
    private val chargers = SampleData.homeChargers.map { Charger(SampleData.HOME_STATION_ID, it) }.associateBy { it.key }
    private val accounts = mutableMapOf<String, Account>()
    private val audit = mutableListOf<AuditEntry>()
    private val hm = SimpleDateFormat("HH:mm", Locale.KOREA)

    init { reset() }

    /** 시나리오 처음 상태로 되돌린다. */
    fun reset() {
        accounts.clear(); audit.clear()
        chargers.values.forEach { c ->
            c.session = null; c.physicalStartedAt = null; c.queue.clear(); c.awaitingArrivalCheck = false
            c.availableAt = null; c.emptyReports.clear()
            c.outOfService = c.def.chgerId in SampleData.outOfService
        }
        SampleData.background.forEach { b ->
            val c = chargers[SampleData.key(SampleData.HOME_STATION_ID, b.chgerId)] ?: return@forEach
            val owner = "resident-${b.chgerId}"
            c.physicalStartedAt = dayStart + b.startMin * MINUTE
            if (b.shared) {
                c.session = Session(
                    ownerId = owner, startedAt = c.physicalStartedAt!!,
                    predictedCompletionAt = b.completionMin?.let { dayStart + it * MINUTE },
                    plannedDepartureAt = b.departureMin?.let { dayStart + it * MINUTE }, shared = true,
                )
                accounts[owner] = Account(b.ownerScore ?: policy.initialScore)
            }
        }
    }

    /** 시연 기기의 과거 기록을 미리 넣는다 (개인별 지연 보정 확인용). */
    fun seedAccount(deviceId: String, score: Int, history: List<HistoryEntry>) {
        accounts[deviceId] = Account(score, history.toMutableList())
    }

    fun auditLog(): List<AuditEntry> = audit.toList()

    // ---------------- 시뮬레이터 전용: 공공데이터 층 ----------------

    /** 차가 실제로 충전기에 꽂혔다 (공공 API 상태가 '충전 중'으로 바뀌고 nowTsdt가 생긴 것과 같다). */
    suspend fun simPhysicalPlug(key: String, at: Long) = mutex.withLock {
        val c = chargers[key] ?: return@withLock
        if (!c.outOfService && c.physicalStartedAt == null) c.physicalStartedAt = at
    }

    /** 약속 없이 꽂혀 있던 차가 충전기를 뺐다. */
    suspend fun simPhysicalUnplug(key: String) = mutex.withLock {
        val c = chargers[key] ?: return@withLock
        if (c.session == null) c.physicalStartedAt = null
    }

    // ---------------- 내부 도우미 ----------------

    private fun account(id: String) = accounts.getOrPut(id) { Account(policy.initialScore) }

    private fun charger(key: String): Charger =
        chargers[key] ?: throw BackendException(404, "충전기를 찾을 수 없습니다.")

    private fun t(ms: Long) = hm.format(ms)

    private fun emit(to: String, type: EventType, title: String, body: String, chargerKey: String?, alternatives: List<String> = emptyList()) {
        account(to).events += AppEvent(UUID.randomUUID().toString(), clock.now(), type, title, body, chargerKey, alternatives)
    }

    private fun record(actor: String, action: String, target: String, before: Any?, after: Any?) {
        audit += AuditEntry(clock.now(), actor, action, target, before?.toString() ?: "-", after?.toString() ?: "-")
    }

    private fun status(c: Charger): ChargerStatus = when {
        c.outOfService -> ChargerStatus.OUT_OF_SERVICE
        c.session != null || c.physicalStartedAt != null -> ChargerStatus.IN_USE
        else -> ChargerStatus.AVAILABLE
    }

    private fun sortedQueue(c: Charger) =
        c.queue.sortedBy { it.at }

    private fun holdDeadline(c: Charger, first: QueueEntry): Long? {
        val freed = c.availableAt ?: return null
        return maxOf(freed, first.etaAt ?: freed) + policy.queueHoldMinutes * MINUTE
    }

    private fun view(c: Charger, me: String): ChargerView {
        val s = c.session
        val q = sortedQueue(c)
        val pos = q.indexOfFirst { it.deviceId == me }.takeIf { it >= 0 }?.plus(1)
        val shared = s?.shared == true && s.plannedDepartureAt != null
        val started = s?.startedAt ?: c.physicalStartedAt
        val st = status(c)
        return ChargerView(
            chargerKey = c.key, label = c.def.label, typeLabel = c.def.typeLabel, powerKw = c.def.powerKw,
            status = st, slot = c.def.slot,
            chargingStartedAt = started,
            predictedCompletionAt = s?.takeIf { shared }?.predictedCompletionAt,
            plannedDepartureAt = s?.takeIf { shared }?.plannedDepartureAt,
            planShared = shared,
            queueSize = q.size, myQueuePosition = pos, isMine = s?.ownerId == me,
            departure = s?.verdict ?: DepartureVerdict.PARKED,
            awaitingArrivalCheck = c.awaitingArrivalCheck,
            ownerScore = if (shared) account(s!!.ownerId).score else null,
            estimatedCompletionAt = if (st == ChargerStatus.IN_USE && !shared && started != null)
                ChargeRefiner.estimateForUnshared(started, c.def.powerKw, policy) else null,
            extensionsUsed = if (s?.ownerId == me) s.extensions else 0,
        )
    }

    private fun checkTime(observedAt: Long) {
        if (kotlin.math.abs(observedAt - clock.now()) > 10 * MINUTE) throw BackendException(400, "요청 시각이 올바르지 않습니다.")
    }

    // ---------------- API ----------------

    suspend fun board(me: String, stationId: String): StationBoard = mutex.withLock {
        if (stationId != SampleData.HOME_STATION_ID) throw BackendException(404, "충전소를 찾을 수 없습니다.")
        StationBoard(stationId, "그린빌 아파트 지하 2층 충전소", true, 2, 3,
            chargers.values.map { view(it, me) }, clock.now(), DataSource.SAMPLE)
    }

    suspend fun nearby(): NearbyResult = mutex.withLock {
        NearbyResult(SampleData.nearby(dayStart), clock.now(), DataSource.SAMPLE, SampleData.homePin)
    }

    /** FR-8: 꽂은 시각 ± N분 안에 '충전 중'으로 바뀌었고 아직 약속이 없는 충전기. 가까운 시각 순. */
    suspend fun match(me: String, stationId: String, pluggedAt: Long): MatchResult = mutex.withLock {
        if (stationId != SampleData.HOME_STATION_ID) throw BackendException(404, "충전소를 찾을 수 없습니다.")
        val window = policy.matchWindowMinutes * MINUTE
        val found = chargers.values
            .filter { c -> !c.outOfService && c.session == null && c.physicalStartedAt?.let { kotlin.math.abs(it - pluggedAt) <= window } == true }
            .sortedBy { kotlin.math.abs(it.physicalStartedAt!! - pluggedAt) }
            .map { view(it, me) }
        MatchResult(found, policy.matchWindowMinutes)
    }

    suspend fun upsertSession(me: String, req: SessionRequest): ChargerView = mutex.withLock {
        val c = charger(req.chargerKey)
        val now = clock.now()
        if (req.chargingStartedAt > now + 10 * MINUTE) throw BackendException(400, "요청 시각이 올바르지 않습니다.")
        if (req.plannedDepartureAt < req.predictedCompletionAt || req.predictedCompletionAt < req.chargingStartedAt)
            throw BackendException(400, "시각 값이 올바르지 않습니다.")
        val cap = DepartureRules.maxDeparture(req.chargingStartedAt, req.predictedCompletionAt, c.def.powerKw, policy)
        if (req.plannedDepartureAt > cap) {
            throw BackendException(400,
                if (DepartureRules.limitedByLaw(req.chargingStartedAt, req.predictedCompletionAt, c.def.powerKw, policy))
                    "법정 충전 시간 기준 때문에 ${t(cap)}까지만 정할 수 있어요."
                else "출차 예정은 완충 후 최대 ${policy.maxDepartureBufferMinutes}분(${t(cap)})까지 정할 수 있어요.")
        }
        val s = c.session
        when {
            c.outOfService -> throw BackendException(409, "운영 중지된 충전기입니다.")
            s == null -> {
                if (chargers.values.any { it.session?.ownerId == me }) throw BackendException(409, "이미 다른 충전기를 사용 중입니다.")
                c.session = Session(me, req.chargingStartedAt, req.predictedCompletionAt, req.plannedDepartureAt, shared = true)
                if (c.physicalStartedAt == null) c.physicalStartedAt = req.chargingStartedAt
                c.awaitingArrivalCheck = false; c.availableAt = null; c.emptyReports.clear()
                c.queue.removeAll { it.deviceId == me }
                record(me, "SESSION_START", c.key, null, t(req.plannedDepartureAt))
            }
            s.ownerId != me -> throw BackendException(403, "다른 차량이 사용 중인 충전기입니다.")
            else -> {
                val before = s.plannedDepartureAt
                val recalc = req.reason == ChangeReason.RECALC
                if (before != null && before != req.plannedDepartureAt) {
                    DepartureRules.checkChange(before, req.plannedDepartureAt, now, s.extensions, recalc, policy)
                        ?.let { throw BackendException(409, it) }
                    if (s.changes >= policy.maxDepartureChangesPerSession) throw BackendException(429, "출차 시각 변경 횟수를 넘었습니다.")
                }
                s.predictedCompletionAt = req.predictedCompletionAt
                if (before != req.plannedDepartureAt) {
                    val extension = before != null && DepartureRules.isExtension(before, req.plannedDepartureAt, recalc)
                    s.plannedDepartureAt = req.plannedDepartureAt
                    s.changes++; s.preNotified = false; s.graceNotified = false
                    if (extension) s.extensions++
                    record(me, if (extension) "DEPARTURE_EXTEND" else if (recalc) "DEPARTURE_RECALC" else "DEPARTURE_CHANGE",
                        c.key, before?.let(::t), t(req.plannedDepartureAt))
                    val change = "${before?.let(::t) ?: "-"} → ${t(req.plannedDepartureAt)}"
                    c.queue.forEach {
                        when {
                            extension -> emit(it.deviceId, EventType.DEPARTURE_CHANGED,
                                "${c.def.label} 출차 ${(req.plannedDepartureAt - before!!) / MINUTE}분 연장",
                                "$change (연장 ${s.extensions}/${policy.maxExtensions}회)", c.key)
                            recalc -> emit(it.deviceId, EventType.DEPARTURE_CHANGED, "${c.def.label} 출차 예정 변경",
                                "충전 속도가 바뀌어 $change 으로 조정됐습니다.", c.key)
                            else -> emit(it.deviceId, EventType.DEPARTURE_CHANGED, "${c.def.label} 출차 시각 변경",
                                "$change 으로 바뀌었습니다.", c.key)
                        }
                    }
                }
            }
        }
        view(c, me)
    }

    suspend fun reportDeparture(me: String, r: DepartureReport): DepartureResult = mutex.withLock {
        val c = charger(r.chargerKey)
        val s = c.session ?: throw BackendException(404, "진행 중인 충전이 없습니다.")
        if (s.ownerId != me) throw BackendException(403, "권한이 없습니다.")
        checkTime(r.observedAt)
        val verdict = DepartureDetector.evaluate(DepartureSignals(r.chargingEnded, r.unplugged, r.leftZone, r.gearMoving))
        val acc = account(me)
        val before = acc.score
        s.verdict = verdict
        if (verdict != DepartureVerdict.CONFIRMED) return@withLock DepartureResult(verdict, before, before)
        confirmDeparture(c, s, me, r.observedAt)
        DepartureResult(verdict, before, acc.score)
    }

    private fun confirmDeparture(c: Charger, s: Session, actor: String, at: Long) {
        val owner = account(s.ownerId)
        val before = owner.score
        val promised = s.plannedDepartureAt
        if (promised != null) {
            val extended = s.extensions > 0
            owner.score = MannerScore.apply(before, promised, at, policy, extended)
            owner.history += HistoryEntry(c.key, s.predictedCompletionAt ?: at, promised, at, before, owner.score)
            record(actor, "SCORE", s.ownerId, before, owner.score)
            val late = Math.floorDiv(at - promised, MINUTE)
            emit(s.ownerId, EventType.SCORE_CHANGED, "매너볼트 ${signed(owner.score - before)}",
                when {
                    late > policy.onTimeToleranceMinutes -> "약속보다 ${late}분 늦었습니다. 현재 ${owner.score}V"
                    extended -> "약속한 ${t(promised)}을 지켰습니다. 연장한 충전이라 보너스는 없어요. 현재 ${owner.score}V"
                    else -> "약속한 ${t(promised)}을 지켰습니다. 현재 ${owner.score}V"
                }, c.key)
        }
        record(actor, "DEPARTURE_CONFIRMED", c.key, "IN_USE", "AVAILABLE")
        c.session = null
        c.physicalStartedAt = null
        c.awaitingArrivalCheck = true
        c.availableAt = at
        c.emptyReports.clear()
        sortedQueue(c).forEachIndexed { i, q ->
            val body = if (i == 0) {
                val until = holdDeadline(c, q)?.let { "${t(it)}까지 도착하면 자리를 맡아 둬요. " } ?: ""
                "출차가 확인되었습니다. ${until}도착하면 자리 상태를 눌러 주세요."
            } else "출차가 확인되었습니다. 대기 ${i + 1}순위입니다."
            emit(q.deviceId, EventType.CHARGER_AVAILABLE, "${c.def.label} 충전기 이용 가능", body, c.key)
        }
    }

    private fun signed(n: Int) = if (n >= 0) "+$n" else "$n"

    suspend fun joinQueue(me: String, key: String, etaAt: Long? = null) = mutex.withLock {
        val c = charger(key)
        c.queue.firstOrNull { it.deviceId == me }?.let { it.etaAt = etaAt ?: it.etaAt; return@withLock }
        when {
            status(c) != ChargerStatus.IN_USE -> throw BackendException(409, "사용 중인 충전기에만 대기 등록할 수 있습니다.")
            c.session?.ownerId == me -> throw BackendException(409, "내 차량이 사용 중인 충전기입니다.")
            chargers.values.any { ch -> ch.queue.any { it.deviceId == me } } -> throw BackendException(409, "다른 충전기에 이미 대기 중입니다.")
            c.queue.size >= policy.waitlistMax -> throw BackendException(409, "대기 인원이 가득 찼습니다.")
        }
        c.queue += QueueEntry(me, clock.now(), etaAt)
        record(me, "QUEUE_JOIN", key, null, etaAt)
    }

    suspend fun leaveQueue(me: String, key: String) = mutex.withLock {
        val c = charger(key)
        if (c.queue.removeAll { it.deviceId == me }) record(me, "QUEUE_LEAVE", key, null, null)
    }

    suspend fun arrivalCheck(me: String, check: ArrivalCheck) = mutex.withLock {
        val c = charger(check.chargerKey)
        val s = c.session
        when {
            s == null && check.observed == Observation.EMPTY -> {
                if (c.awaitingArrivalCheck) {
                    c.awaitingArrivalCheck = false; c.availableAt = null
                    c.queue.removeAll { it.deviceId == me }
                    record(me, "ARRIVAL_CONFIRMED", c.key, "AVAILABLE", "EMPTY")
                    emit(me, EventType.ARRIVAL_CONFIRMED, "${c.def.label} 기록 확정", "자리가 비어 있음을 확인했습니다. 충전기를 연결해 주세요.", c.key)
                }
            }
            s == null && check.observed == Observation.OCCUPIED -> {
                record(me, "MISMATCH", c.key, "AVAILABLE", "OCCUPIED")
                emit(me, EventType.MISMATCH_RECORDED, "불일치 기록", "차량 데이터는 출차로 판단했지만 자리가 차 있다고 알려 주셨습니다. 기록해 두었습니다.", c.key)
            }
            s != null && check.observed == Observation.EMPTY -> {
                if (s.ownerId == me) throw BackendException(409, "내 차량의 충전기입니다.")
                c.emptyReports += me
                record(me, "MISMATCH", c.key, "IN_USE", "EMPTY")
                if (c.emptyReports.size >= policy.emptyReportsToOverride) {
                    confirmDeparture(c, s, "reports", clock.now())
                } else {
                    emit(me, EventType.MISMATCH_RECORDED, "불일치 기록", "차량 데이터로는 아직 출차 전입니다. 신고를 기록했습니다.", c.key)
                }
            }
            else -> Unit // 사용 중 + 아직 있음: 데이터와 일치
        }
    }

    suspend fun me(me: String): Profile = mutex.withLock {
        val a = account(me)
        Profile(me, a.score, a.history.toList())
    }

    suspend fun events(me: String, since: Long): List<AppEvent> = mutex.withLock {
        account(me).events.filter { it.at > since }
    }

    /** 1분 주기 Sweeper와 같은 역할: 사전 알림, 충전 완료 안내, 유예 만료 대체 안내, 찜 자동 취소 (FR-3.3, 5.3, 12.4). */
    suspend fun tick() = mutex.withLock {
        val now = clock.now()
        chargers.values.forEach { c ->
            holdExpiry(c, now)
            val s = c.session ?: return@forEach
            val planned = s.plannedDepartureAt ?: return@forEach
            if (!s.shared) return@forEach
            val completion = s.predictedCompletionAt
            if (!s.completeNotified && completion != null && now >= completion) {
                s.completeNotified = true
                emit(s.ownerId, EventType.CHARGE_COMPLETE, "충전 완료", "출차 예정 ${t(planned)}입니다. 충전기를 분리하고 이동해 주세요.", c.key)
            }
            if (!s.preNotified && now >= planned - policy.preNotifyMinutes * MINUTE) {
                s.preNotified = true
                c.queue.forEach {
                    emit(it.deviceId, EventType.PRE_DEPARTURE, "${c.def.label} 곧 비어요",
                        "출차 예정 ${t(planned)} (${policy.preNotifyMinutes}분 전 알림)", c.key)
                }
            }
            if (!s.graceNotified && now >= planned + policy.graceMinutes * MINUTE) {
                s.graceNotified = true
                val first = sortedQueue(c).firstOrNull() ?: return@forEach
                emit(first.deviceId, EventType.GRACE_EXPIRED, "${c.def.label} 출차 지연",
                    "유예 ${policy.graceMinutes}분이 지나도 출차가 확인되지 않았습니다. 가까운 다른 충전기를 안내합니다.", c.key,
                    alternatives(c.key))
            }
        }
    }

    /** FR-12.4: 빈 충전기의 대기 1순위가 도착 예정(없으면 빈 시각) + N분 안에 오지 않으면 찜을 취소하고 다음 순번에게 넘긴다. */
    private fun holdExpiry(c: Charger, now: Long) {
        if (c.session != null || !c.awaitingArrivalCheck) return
        val first = sortedQueue(c).firstOrNull() ?: return
        val deadline = holdDeadline(c, first) ?: return
        if (now <= deadline) return
        c.queue.remove(first)
        record("system", "QUEUE_EXPIRED", c.key, first.deviceId, null)
        emit(first.deviceId, EventType.QUEUE_CANCELLED, "${c.def.label} 찜 자동 취소",
            "도착 예정 시각(${t(deadline)})이 지나 찜이 취소됐어요. 다음 순번에게 넘어갑니다.", c.key)
        c.availableAt = now
        sortedQueue(c).firstOrNull()?.let { next ->
            emit(next.deviceId, EventType.CHARGER_AVAILABLE, "${c.def.label} 충전기 이용 가능",
                "앞 순번이 오지 않아 1순위가 되었어요. ${holdDeadline(c, next)?.let(::t)}까지 도착해 주세요.", c.key)
        }
    }

    private fun alternatives(exclude: String): List<String> {
        val here = chargers.values.filter { it.key != exclude && status(it) == ChargerStatus.AVAILABLE }
            .map { "같은 단지 ${it.def.label} (${it.def.typeLabel})" }
        // 충전소마다 한 줄 (빈 충전기가 여러 대여도 같은 충전소가 반복되지 않게)
        val near = SampleData.nearby(dayStart).mapNotNull { st ->
            val free = st.chargers.filter { it.status == ChargerStatus.AVAILABLE }
            val best = free.maxByOrNull { it.outputKw } ?: return@mapNotNull null
            val kind = if (free.size == 1) "${best.typeLabel} ${best.outputKw.toInt()}kW"
            else "${if (best.outputKw >= 40) "급속" else "완속"} ${best.outputKw.toInt()}kW · 빈 충전기 ${free.size}대"
            "${st.name} ${st.distanceLabel} ($kind)"
        }
        return (here + near).take(3)
    }
}

/** 기기 하나의 창구. */
class LocalBackend(private val server: LocalServer, override val deviceId: String) : MannerVoltBackend {
    override suspend fun board(stationId: String) = server.board(deviceId, stationId)
    override suspend fun nearby() = server.nearby()
    override suspend fun match(stationId: String, pluggedAt: Long) = server.match(deviceId, stationId, pluggedAt)
    override suspend fun upsertSession(req: SessionRequest) = server.upsertSession(deviceId, req)
    override suspend fun reportDeparture(report: DepartureReport) = server.reportDeparture(deviceId, report)
    override suspend fun joinQueue(chargerKey: String, etaAt: Long?) = server.joinQueue(deviceId, chargerKey, etaAt)
    override suspend fun leaveQueue(chargerKey: String) = server.leaveQueue(deviceId, chargerKey)
    override suspend fun arrivalCheck(check: ArrivalCheck) = server.arrivalCheck(deviceId, check)
    override suspend fun me() = server.me(deviceId)
    override suspend fun events(since: Long) = server.events(deviceId, since)
}
