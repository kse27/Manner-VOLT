package com.mannervolt

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mannervolt.backend.AppEvent
import com.mannervolt.backend.ArrivalCheck
import com.mannervolt.backend.BackendException
import com.mannervolt.backend.ChangeReason
import com.mannervolt.backend.ChargerStatus
import com.mannervolt.backend.ChargerView
import com.mannervolt.backend.DemoBackend
import com.mannervolt.backend.DemoCommand
import com.mannervolt.backend.DemoConfig
import com.mannervolt.backend.DepartureReport
import com.mannervolt.backend.EventType
import com.mannervolt.backend.HistoryEntry
import com.mannervolt.backend.LocalBackend
import com.mannervolt.backend.LocalServer
import com.mannervolt.backend.MannerVoltBackend
import com.mannervolt.backend.NearbyResult
import com.mannervolt.backend.Observation
import com.mannervolt.backend.Profile
import com.mannervolt.backend.RemoteBackend
import com.mannervolt.backend.SampleData
import com.mannervolt.backend.SessionRequest
import com.mannervolt.backend.StationBoard
import com.mannervolt.config.Policy
import com.mannervolt.domain.ChargeInput
import com.mannervolt.domain.ChargePredictor
import com.mannervolt.domain.ChargeRefiner
import com.mannervolt.domain.DelaySample
import com.mannervolt.domain.DepartureDetector
import com.mannervolt.domain.DepartureRecommender
import com.mannervolt.domain.DepartureRules
import com.mannervolt.domain.DepartureSignals
import com.mannervolt.domain.DepartureVerdict
import com.mannervolt.domain.Geo
import com.mannervolt.domain.MINUTE
import com.mannervolt.sim.SimClock
import com.mannervolt.vehicle.PleosNaviSource
import com.mannervolt.vehicle.PleosReadings
import com.mannervolt.vehicle.PleosVehicleDataSource
import com.mannervolt.vehicle.SimulatedVehicle
import com.mannervolt.vehicle.VehicleSnapshot
import com.mannervolt.vehicle.VoiceGuide
import com.mannervolt.vehicle.ZoneMonitor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID

enum class Role(val title: String, val subtitle: String) {
    A("운전자 A", "충전 중인 차량"),
    B("운전자 B", "충전을 기다리는 차량"),
}

enum class Tab(val title: String) { HOME("내 충전"), BOARD("충전소 배치도"), NEARBY("주변 충전소"), MANNER("매너볼트"), SIM("시뮬레이터") }

/** 충전 완료 시각이 실제 충전 속도로 바뀌어 출차 시각 변경을 제안하는 상태 (FR-9.3). */
data class RecalcProposal(val oldCompletionAt: Long, val newCompletionAt: Long, val newDepartureAt: Long)

/** 하차 전 출차 시각 설정 상태 (FR-1). */
data class PlanState(
    val chargerKey: String,
    val chargerLabel: String,
    val startedAt: Long,
    val completionAt: Long,
    val recommendedAt: Long,
    val personalDelayMinutes: Int,
    val selectedDepartureAt: Long,
    val powerKw: Double,
    val socAtStart: Double,
    /** FR-12.2 고를 수 있는 가장 늦은 출차 시각 */
    val maxDepartureAt: Long,
    val limitedByLaw: Boolean,
    val confirmedDepartureAt: Long? = null,
    /** FR-9 실제 충전 속도로 확인했는지 (false 면 충전기 출력 기준 추정) */
    val measured: Boolean = false,
    val note: String? = null,
    val proposal: RecalcProposal? = null,
    val extensionsUsed: Int = 0,
    val lastRefineAt: Long = startedAt,
    /** socAtStart를 잰 실제 시각 */
    val measuredFrom: Long = startedAt,
) {
    val confirmed get() = confirmedDepartureAt != null
}

/** FR-8 꽂은 직후 충전기 확인 상태 */
data class MatchState(
    val pluggedAt: Long,
    val candidates: List<ChargerView>,
    val showAll: Boolean,
    /** 꽂은 순간의 실제 시각과 배터리 % (FR-9 실제 충전 속도 계산의 기준) */
    val measuredFrom: Long = pluggedAt,
    val socAtPlug: Double = 0.0,
)

/** FR-11 퇴근길 도착 예정과 충전기가 비는 시각 비교 */
data class ArrivalInfo(
    val etaAt: Long,
    val fromNavi: Boolean,
    val chargerLabel: String,
    /** null 이면 지금 비어 있음 */
    val freeAt: Long?,
    val ownerScore: Int?,
    val estimated: Boolean,
    val waitMinutes: Long,
    val queuePosition: Int?,
)

data class ScenarioStep(val title: String, val detail: String)

data class UiState(
    val tab: Tab = Tab.HOME,
    val role: Role = Role.A,
    val now: Long = 0,
    val speed: Double = 1.0,
    val remote: Boolean = false,
    /** 두 대 시연: 이 기기가 맡은 차량 (null 이면 한 대 시연 또는 서버 모드) */
    val demoRole: Role? = null,
    val usePleos: Boolean = false,
    val pleos: PleosReadings = PleosReadings(),
    val naviConnected: Boolean? = null,
    val vehicle: VehicleSnapshot? = null,
    val leftZone: Boolean = false,
    val selectedChargerKey: String = SampleData.SCENARIO_CHARGER,
    val match: MatchState? = null,
    val plan: PlanState? = null,
    val lastVerdict: DepartureVerdict? = null,
    val board: StationBoard? = null,
    val nearby: NearbyResult? = null,
    val profile: Profile? = null,
    val events: List<AppEvent> = emptyList(),
    val banner: AppEvent? = null,
    val driving: Boolean = false,
    val arrival: ArrivalInfo? = null,
    val voice: String? = null,
    val scenarioIndex: Int = 0,
    val message: String? = null,
    val policy: Policy = Policy(),
)

class AppController(app: Application) : AndroidViewModel(app) {

    val policy: Policy = runCatching {
        app.assets.open("policy.json").bufferedReader().use { Policy.parse(it.readText()) }
    }.getOrElse { Policy() }

    private val dayStart: Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    private fun at(h: Int, m: Int) = dayStart + (h * 60 + m) * MINUTE

    val clock = SimClock(at(19, 0))
    private val remoteUrl = BuildConfig.API_BASE_URL
    /** 두 대 시연 (영상 촬영용): Mac 시연 서버가 상태와 시계를 공유한다 */
    private val demo: DemoConfig? = DemoConfig.current
    private val demoBackend: DemoBackend? = demo?.let { DemoBackend(it) }
    private val local get() = remoteUrl.isBlank() && demo == null
    private val server = LocalServer(policy, clock, dayStart)
    private val pleosSource = PleosVehicleDataSource(app)
    private val naviSource = PleosNaviSource(app, viewModelScope)
    private val voice = VoiceGuide(app)
    private val zoneMonitor = ZoneMonitor(app)
    private val hm = SimpleDateFormat("HH:mm", Locale.KOREA)
    private val spokenTime = SimpleDateFormat("H시 m분", Locale.KOREA)

    /** 역할별 차량 + 백엔드 창구. 원격 모드에서는 이 기기 하나(A)만 쓴다. */
    private inner class Agent(val role: Role, val backend: MannerVoltBackend, val vehicle: SimulatedVehicle) {
        var plan: PlanState? = null
        var match: MatchState? = null
        var lastPlugged = false
        var zoneCenter: Pair<Double, Double>? = null
        var lastReported: DepartureSignals? = null
        var lastVerdict: DepartureVerdict? = null
        var lastEventAt = 0L
        val events = ArrayDeque<AppEvent>()
        val seenEvents = HashSet<String>()
        /** 다른 역할 화면을 보는 동안(= 차에 없는 동안) 도착한 최신 알림. 다음 탑승 때 보여 준다 (FR-10.2). */
        var unseen: AppEvent? = null
        var profile: Profile? = null
        var selectedChargerKey = SampleData.SCENARIO_CHARGER
        /** 시뮬레이터 내비: 목적지(집) 도착 예정 시각 */
        var simEtaAt: Long? = null
        var queuedKey: String? = null
        var lastEtaSent: Long? = null
        var wasDriving = false
    }

    private fun homeVehicle(soc: Double, lat: Double, lon: Double) = VehicleSnapshot(
        socPercent = soc, targetPercent = 80, batteryCapacityKwh = 63.0, plugged = false, charging = false,
        chargerPowerKw = 7.0, outsideTempC = 8.0, underground = true, lat = lat, lon = lon,
    )

    private val agents: Map<Role, Agent> = run {
        val a = SimulatedVehicle(homeVehicle(40.0, SampleData.HOME_LAT, SampleData.HOME_LON), policy)
        val b = SimulatedVehicle(homeVehicle(25.0, SampleData.HOME_LAT + 0.03, SampleData.HOME_LON), policy)
        if (demo != null && demoBackend != null) {
            mapOf(demo.role to Agent(demo.role, demoBackend, if (demo.role == Role.A) a else b))
        } else if (!local) {
            mapOf(Role.A to Agent(Role.A, RemoteBackend(app, remoteUrl), a))
        } else {
            mapOf(
                Role.A to Agent(Role.A, LocalBackend(server, "demo-device-a"), a),
                Role.B to Agent(Role.B, LocalBackend(server, "demo-device-b"), b),
            )
        }
    }

    /** 실제 차량 데이터 모드에서 Pleos 내비 도착 시간을 쓰는 역할: 시연은 기다리는 운전자 B, 서버 모드는 이 기기(A). */
    private val naviRole = demo?.role ?: if (local) Role.B else Role.A

    private val _ui = MutableStateFlow(UiState(remote = remoteUrl.isNotBlank() && demo == null, demoRole = demo?.role,
        role = demo?.role ?: Role.A, policy = policy))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val current get() = agents.getValue(_ui.value.role)

    val scenario = listOf(
        ScenarioStep("준비", "19:00, 운전자 A가 지하 2층 충전소에 도착합니다."),
        ScenarioStep("① A 충전기 연결", "꽂은 시각에 '충전 중'으로 바뀐 충전기를 찾아 \"3번 충전기 맞나요?\" → 추정 완료 23:00, 추천 23:10 → 확정"),
        ScenarioStep("①' 19:10 실제 충전 속도로 확인", "배터리 상승 속도로 다시 계산 → 예상 그대로 23:00"),
        ScenarioStep("② 22:30 B 출발 전 3번 찜", "B가 퇴근 전 배치도에서 3번(23:10 빔, 매너볼트 104V)을 찜. 도착 예정 시각도 함께 보냄"),
        ScenarioStep("②' 22:31 B 퇴근길 운전 중", "터치 없는 카드 한 장 + 음성: 집 도착 22:58 · 3번 23:10 빔 → 도착 후 약 12분 대기"),
        ScenarioStep("②'' 22:55 사전 알림", "출차 15분 전, 운전 중인 B에게 음성으로 알림"),
        ScenarioStep("③ 23:00 충전 완료", "A에게 출차 안내 (B는 22:58 도착해 주차)"),
        ScenarioStep("③' 23:08 A 출차", "충전기 분리 + 기어 D + 구역 이탈 → 출차 확인 → B에게 이용 가능 알림"),
        ScenarioStep("③'' 23:12 B 도착 확인", "B가 '자리 비어 있음'을 눌러 기록 확정"),
        ScenarioStep("④ 매너볼트 반영", "약속 23:10, 실제 23:08 → 104V → 106V, 다음 추천에 반영"),
    )

    init {
        resetDemo()
        pleosSource.start()
        viewModelScope.launch { pleosSource.readings.collect { r -> _ui.update { it.copy(pleos = r) } } }
        viewModelScope.launch { naviSource.connected.collect { c -> _ui.update { it.copy(naviConnected = c) } } }
        viewModelScope.launch { voice.last.collect { v -> _ui.update { it.copy(voice = v) } } }
        viewModelScope.launch { delay(5_000); pleosSource.markDisconnectedIfUnknown() }
        viewModelScope.launch { loop() }
    }

    override fun onCleared() {
        pleosSource.stop(); zoneMonitor.stop(); naviSource.release(); voice.release()
    }

    // ------------------------------------------------------------------ loop

    private var lastNow = clock.now()
    private var lastRefresh = 0L

    private suspend fun loop() {
        while (true) {
            runCatching { step() }.onFailure { e -> showError(e) }
            delay(500)
        }
    }

    private var lastCommandSeq = 0L

    /** 두 대 시연: 서버 시계에 맞추고, 감독 패널이 보낸 차량 조작 명령을 실행한다. */
    private suspend fun syncDemo() {
        val b = demoBackend ?: return
        val s = b.sync(lastCommandSeq)
        // 시연 서버를 다시 켜면 명령 번호가 처음부터 시작한다
        if (s.latest < lastCommandSeq) { lastCommandSeq = 0; return }
        if (kotlin.math.abs(clock.now() - s.now) > 1_500 || clock.speed != s.speed) {
            clock.setSpeed(s.speed); clock.jumpTo(s.now)
        }
        s.commands.sortedBy { it.seq }.forEach { c ->
            lastCommandSeq = maxOf(lastCommandSeq, c.seq)
            runCatching { applyCommand(c) }.onFailure(::showError)
        }
    }

    private suspend fun applyCommand(c: DemoCommand) {
        val a = current
        val arg = c.arg?.content
        when (c.action) {
            "reset" -> { resetDemo(); lastNow = clock.now() }
            "tab" -> arg?.let { t -> runCatching { Tab.valueOf(t) }.getOrNull()?.takeIf { it != Tab.SIM }?.let { selectTab(it) } }
            "plug" -> a.vehicle.plugIn()
            "unplug" -> a.vehicle.unplug()
            "depart" -> simLeaveZone(a.role)
            "arrive" -> simArriveHome(a.role)
            "drive" -> a.vehicle.moveTo(SampleData.HOME_LAT + 0.03, SampleData.HOME_LON, "D")
            "park" -> simPark(a.role)
            "setEta" -> a.simEtaAt = clock.now() + (arg?.toIntOrNull() ?: 28) * MINUTE
            "power" -> a.vehicle.update { it.copy(powerFactor = arg?.toDoubleOrNull() ?: 1.0) }
            "ensureConfirmed" -> ensureAConfirmed(a)
            "joinQueue" -> arg?.let { key ->
                val eta = etaOf(a)?.first
                a.backend.joinQueue(key, eta); a.queuedKey = key; a.lastEtaSent = eta
            }
            "arrivalCheck" -> arg?.let { a.backend.arrivalCheck(ArrivalCheck(it, Observation.EMPTY)) }
        }
        lastRefresh = 0
    }

    private suspend fun step() {
        syncDemo()
        val now = clock.now()
        val elapsed = now - lastNow
        lastNow = now
        agents.values.forEach { it.vehicle.advance(elapsed) }
        if (local) {
            // 시각을 건너뛰면 1분씩 나눠 tick을 돌린다. 사전 알림 같은 예약 알림이 원래 울렸어야 할 시각으로 기록된다.
            if (elapsed > MINUTE && elapsed <= 24 * 60 * MINUTE) {
                var t = now - elapsed + MINUTE
                while (t < now) { clock.jumpTo(t); server.tick(); t += MINUTE }
                clock.jumpTo(now)
            }
            server.tick()
        }
        agents.values.forEach { process(it) }
        val real = System.currentTimeMillis()
        if (real - lastRefresh > 2_000) { lastRefresh = real; refresh() }
        drivingEdges()
        publish()
    }

    /** 역할별 실효 차량 상태. 실제 차량 모드(A)는 Pleos SDK 값과 GPS를 우선한다. */
    private fun effective(agent: Agent): VehicleSnapshot {
        val sim = agent.vehicle.state.value
        if (agent.role != Role.A || !_ui.value.usePleos) return sim
        val p = _ui.value.pleos
        val soc = p.socPercent?.toDouble() ?: sim.socPercent
        val plugged = p.chargePortOpen ?: sim.plugged
        val loc = zoneMonitor.location.value
        return sim.copy(
            socPercent = soc, plugged = plugged,
            charging = plugged && (p.chargeSwitchOn ?: sim.charging) && soc < sim.targetPercent,
            gear = p.gear ?: sim.gear,
            lat = loc?.first ?: sim.lat, lon = loc?.second ?: sim.lon,
        )
    }

    private fun isDriving(agent: Agent) = DepartureDetector.gearMoving(effective(agent).gear)

    /** 도착 예정 시각: 실제 차량 모드는 Pleos 내비, 아니면 시뮬레이터 내비. */
    private fun etaOf(agent: Agent): Pair<Long, Boolean>? {
        if (_ui.value.usePleos && agent.role == naviRole) {
            naviSource.remaining.value?.let { return clock.now() + it.remainSeconds * 1000L to true }
        }
        return agent.simEtaAt?.let { it to false }
    }

    private fun leftZone(agent: Agent, v: VehicleSnapshot): Boolean {
        val c = agent.zoneCenter ?: (SampleData.HOME_LAT to SampleData.HOME_LON)
        return Geo.isOutside(v.lat, v.lon, c.first, c.second, policy.zoneRadiusMeters)
    }

    private suspend fun process(agent: Agent) {
        val v = effective(agent)
        // FR-8: 꽂으면 같은 시각에 '충전 중'이 된 충전기를 찾는다
        if (v.plugged && !agent.lastPlugged && agent.plan == null && agent.match == null) onPlugged(agent)
        if (!v.plugged && agent.lastPlugged) {
            // 두 대 시연: 충전기를 빼면 공공데이터 상태도 '이용 가능'으로 바뀐다 (약속은 출차 확인 전까지 유지)
            demoBackend?.physical(agent.plan?.chargerKey ?: agent.selectedChargerKey, false, clock.now())
            if (agent.plan == null) {
                if (local) agent.match?.let { server.simPhysicalUnplug(agent.selectedChargerKey) }
                agent.match = null
            }
        }
        agent.lastPlugged = v.plugged

        // FR-9: 실제 충전 속도로 완료 시각 보정
        agent.plan?.let { if (v.charging) refine(agent, it, v) }

        // FR-12.1 출차 판단: 신호가 바뀔 때만 서버에 보고 (구역 이탈 여부만 전송)
        val plan = agent.plan
        if (plan?.confirmed == true) {
            val signals = DepartureSignals(
                chargingEnded = !v.charging, unplugged = !v.plugged, leftZone = leftZone(agent, v),
                gearMoving = DepartureDetector.gearMoving(v.gear),
            )
            if (signals != agent.lastReported && DepartureDetector.evaluate(signals) != DepartureVerdict.PARKED) {
                agent.lastReported = signals
                val r = agent.backend.reportDeparture(
                    DepartureReport(plan.chargerKey, signals.chargingEnded, signals.unplugged, signals.leftZone, clock.now(), signals.gearMoving),
                )
                agent.lastVerdict = r.verdict
                if (r.verdict == DepartureVerdict.CONFIRMED) {
                    agent.plan = null; agent.lastReported = null; agent.zoneCenter = null
                    lastRefresh = 0
                }
            }
        }

        // FR-11.2: 대기 중이면 도착 예정 시각이 바뀔 때 서버에 알려 자리 유지 기준으로 쓴다
        val key = agent.queuedKey
        val eta = etaOf(agent)?.first
        if (key != null && eta != null && (agent.lastEtaSent == null || kotlin.math.abs(eta - agent.lastEtaSent!!) >= 2 * MINUTE)) {
            runCatching { agent.backend.joinQueue(key, eta) }.onSuccess { agent.lastEtaSent = eta }
        }

        // 알림 수신
        val fresh = agent.backend.events(agent.lastEventAt - 1).filter { agent.seenEvents.add(it.id) }
        if (fresh.isNotEmpty()) {
            agent.lastEventAt = maxOf(agent.lastEventAt, fresh.maxOf { it.at })
            val ordered = fresh.sortedBy { it.at } // 같은 시각이면 서버 발생 순서 유지
            ordered.forEach { e ->
                when (e.type) {
                    EventType.QUEUE_CANCELLED, EventType.ARRIVAL_CONFIRMED -> if (e.chargerKey == agent.queuedKey) agent.queuedKey = null
                    else -> Unit
                }
            }
            deliver(agent, ordered)
        }
    }

    /** 알림을 역할의 목록에 넣고, 지금 보는 차량이면 배너(운전 중이면 음성도), 아니면 다음 탑승 때 보여 준다. */
    private fun deliver(agent: Agent, ordered: List<AppEvent>) {
        ordered.forEach { agent.events.addFirst(it) }
        while (agent.events.size > 30) agent.events.removeLast()
        val latest = ordered.last()
        if (agent.role == _ui.value.role) {
            showBanner(latest)
            if (isDriving(agent)) voice.speak("${latest.title}. ${latest.body}")
        } else agent.unseen = latest
    }

    private fun localEvent(agent: Agent, type: EventType, title: String, body: String, key: String?) =
        deliver(agent, listOf(AppEvent("local-" + UUID.randomUUID(), clock.now(), type, title, body, key)))

    private suspend fun onPlugged(agent: Agent) {
        val start = Math.floorDiv(clock.now(), MINUTE) * MINUTE
        // 시연: 차가 실제로 꽂힌 충전기는 시뮬레이터에서 고른 충전기다. 공공데이터 상태가 '충전 중'으로 바뀐다.
        if (local) server.simPhysicalPlug(agent.selectedChargerKey, start)
        demoBackend?.physical(agent.selectedChargerKey, true, start)
        val res = agent.backend.match(SampleData.HOME_STATION_ID, start)
        agent.match = MatchState(start, res.candidates, showAll = res.candidates.isEmpty(),
            measuredFrom = clock.now(), socAtPlug = effective(agent).socPercent)
        if (agent.role == _ui.value.role) _ui.update { it.copy(tab = Tab.HOME) }
        lastRefresh = 0
    }

    private suspend fun historyOf(agent: Agent): List<DelaySample> =
        (agent.profile ?: agent.backend.me()).history.map { DelaySample(it.completionAt, it.actualAt) }

    /** 추천 출차 시각 = 완료 + 개인 지연 (5분 올림), 단 '완충 + 30분'과 법정 기준을 넘지 않는다. */
    private fun recommendWithin(start: Long, completion: Long, power: Double, history: List<DelaySample>): Long =
        DepartureRules.clamp(DepartureRecommender.recommend(completion, history, policy), start, completion, power, policy)

    private suspend fun startPlan(agent: Agent, key: String, start: Long) {
        val v = effective(agent)
        val m = agent.match
        val board = agent.backend.board(SampleData.HOME_STATION_ID)
        val charger = board.chargers.firstOrNull { it.chargerKey == key } ?: return
        if ((charger.planShared && !charger.isMine) || charger.status == ChargerStatus.OUT_OF_SERVICE) {
            _ui.update { it.copy(message = "${charger.label} 충전기는 다른 차량이 출차 약속을 공유 중이에요. 다른 충전기를 고르세요.") }
            return
        }
        // 충전 출력 = 충전기 출력 (공공데이터). 가상 차량도 같은 충전기 출력으로 맞춘다.
        val power = charger.powerKw
        agent.vehicle.update { it.copy(chargerPowerKw = power, underground = board.underground) }
        val completion = ChargePredictor.predictCompletion(
            ChargeInput(start, v.batteryCapacityKwh, (m?.socAtPlug ?: v.socPercent).toInt(), v.targetPercent, power, v.outsideTempC, board.underground),
            policy,
        )
        val history = historyOf(agent)
        val delayMin = DepartureRecommender.personalDelayMinutes(history, policy)
        val rec = recommendWithin(start, completion, power, history)
        agent.zoneCenter = v.lat to v.lon
        agent.selectedChargerKey = key
        agent.match = null
        agent.plan = PlanState(
            chargerKey = charger.chargerKey, chargerLabel = charger.label, startedAt = start, completionAt = completion,
            recommendedAt = rec, personalDelayMinutes = delayMin, selectedDepartureAt = rec, powerKw = power,
            socAtStart = m?.socAtPlug ?: v.socPercent,
            measuredFrom = m?.measuredFrom ?: clock.now(),
            maxDepartureAt = DepartureRules.maxDeparture(start, completion, power, policy),
            limitedByLaw = DepartureRules.limitedByLaw(start, completion, power, policy),
        )
        agent.lastVerdict = null
    }

    /** FR-9: 일정 시간마다 실제 배터리 상승 속도로 완료 시각을 다시 계산한다. */
    private suspend fun refine(agent: Agent, plan: PlanState, v: VehicleSnapshot) {
        val now = clock.now()
        if (plan.proposal != null || now - plan.lastRefineAt < policy.refineAfterMinutes * MINUTE) return
        val newC = ChargeRefiner.refine(plan.measuredFrom, plan.socAtStart, now, v.socPercent, v.targetPercent, policy) ?: return
        val checked = plan.copy(lastRefineAt = now, measured = true)
        if (!ChargeRefiner.changedEnough(plan.completionAt, newC, policy)) {
            agent.plan = if (plan.measured) checked else checked.copy(note = "실제 충전 속도로 확인했어요. 예상 그대로 ${hm.format(plan.completionAt)}이에요.")
            return
        }
        val history = historyOf(agent)
        val rec = recommendWithin(plan.startedAt, newC, plan.powerKw, history)
        val cap = DepartureRules.maxDeparture(plan.startedAt, newC, plan.powerKw, policy)
        val law = DepartureRules.limitedByLaw(plan.startedAt, newC, plan.powerKw, policy)
        val slower = newC > plan.completionAt
        if (!plan.confirmed) {
            agent.plan = checked.copy(
                completionAt = newC, recommendedAt = rec, maxDepartureAt = cap, limitedByLaw = law,
                selectedDepartureAt = if (plan.selectedDepartureAt == plan.recommendedAt) rec
                else DepartureRules.clamp(plan.selectedDepartureAt, plan.startedAt, newC, plan.powerKw, policy),
                note = "실제 충전 속도로 다시 계산했어요 (완료 ${hm.format(plan.completionAt)} → ${hm.format(newC)}).",
            )
        } else {
            agent.plan = checked.copy(proposal = RecalcProposal(plan.completionAt, newC, rec))
            localEvent(agent, EventType.DEPARTURE_CHANGED,
                if (slower) "충전이 느려져서 예상 시간이 바뀌었어요" else "충전이 빨라져서 예상 시간이 바뀌었어요",
                "충전 완료 ${hm.format(plan.completionAt)} → ${hm.format(newC)}. 출차를 ${hm.format(rec)}으로 변경할까요?", plan.chargerKey)
        }
    }

    private suspend fun refresh() {
        agents.values.forEach { a -> a.profile = a.backend.me() }
        val board = current.backend.board(SampleData.HOME_STATION_ID)
        val nearby = if (_ui.value.nearby == null || _ui.value.tab == Tab.NEARBY) current.backend.nearby() else _ui.value.nearby
        _ui.update { it.copy(board = board, nearby = nearby) }
    }

    /** FR-13.2: 지금 보는 차량이 운전을 시작하면 상황을 음성으로 한 번 안내한다. */
    private suspend fun drivingEdges() {
        agents.values.forEach { a ->
            val driving = isDriving(a)
            if (driving && !a.wasDriving && a.role == _ui.value.role) {
                refresh()
                drivingSummary(a)?.let(voice::speak)
            }
            a.wasDriving = driving
        }
    }

    private fun spoken(ms: Long) = spokenTime.format(ms)

    private fun drivingSummary(a: Agent): String? {
        arrivalFor(a, _ui.value.board)?.let { i ->
            val free = i.freeAt?.let { "${i.chargerLabel} 충전기는 ${spoken(it)}에 비어요" + if (i.estimated) " (추정)" else "" }
                ?: "${i.chargerLabel} 충전기는 지금 비어 있어요"
            val tail = if (i.waitMinutes > 0) "도착 후 약 ${i.waitMinutes}분 기다려요." else "도착하면 바로 충전할 수 있어요."
            return "집 도착 예정 ${spoken(i.etaAt)}. $free. $tail"
        }
        return if (a.lastVerdict == DepartureVerdict.CONFIRMED) "출차가 확인됐어요. 안전 운전하세요." else null
    }

    /** FR-11: 도착 예정 시각과 단지 충전기가 비는 시각 비교. 찜한 충전기가 있으면 그 충전기, 없으면 가장 먼저 비는 충전기. */
    private fun arrivalFor(a: Agent, board: StationBoard?): ArrivalInfo? {
        val (eta, fromNavi) = etaOf(a) ?: return null
        if (eta < clock.now()) return null // 도착 예정이 지났으면(도착했거나 경로 종료) 비교하지 않는다
        val chargers = board?.chargers ?: return null
        fun freeAt(c: ChargerView): Long? = when (c.status) {
            ChargerStatus.AVAILABLE -> null
            else -> c.plannedDepartureAt ?: c.estimatedCompletionAt
        }
        val target = chargers.firstOrNull { it.myQueuePosition != null }
            ?: chargers.filter { !it.isMine && it.status != ChargerStatus.OUT_OF_SERVICE }
                .minByOrNull { freeAt(it) ?: 0L } ?: return null
        val free = freeAt(target)
        val wait = free?.let { maxOf(0L, Math.floorDiv(it - eta + MINUTE - 1, MINUTE)) } ?: 0L
        return ArrivalInfo(
            etaAt = eta, fromNavi = fromNavi, chargerLabel = target.label, freeAt = free,
            ownerScore = target.ownerScore, estimated = target.status == ChargerStatus.IN_USE && !target.planShared,
            waitMinutes = wait, queuePosition = target.myQueuePosition,
        )
    }

    private fun publish() {
        val a = current
        val v = effective(a)
        _ui.update {
            it.copy(
                now = clock.now(), speed = clock.speed, vehicle = v, leftZone = leftZone(a, v),
                match = a.match, plan = a.plan, lastVerdict = a.lastVerdict, profile = a.profile, events = a.events.toList(),
                selectedChargerKey = a.selectedChargerKey, driving = isDriving(a), arrival = arrivalFor(a, it.board),
            )
        }
    }

    private fun showBanner(e: AppEvent) {
        _ui.update { it.copy(banner = e) }
        scheduleBannerDismiss()
    }

    private var bannerJob: kotlinx.coroutines.Job? = null
    private fun scheduleBannerDismiss() {
        bannerJob?.cancel()
        bannerJob = viewModelScope.launch { delay(9_000); _ui.update { it.copy(banner = null) } }
    }

    private fun showError(e: Throwable) {
        val msg = (e as? BackendException)?.message ?: "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요."
        _ui.update { it.copy(message = msg) }
    }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block(); lastRefresh = 0; refresh(); publish() }.onFailure(::showError)
        }
    }

    // ------------------------------------------------------------------ UI actions

    fun selectTab(t: Tab) = _ui.update { it.copy(tab = t) }
    fun dismissMessage() = _ui.update { it.copy(message = null) }
    fun dismissBanner() = _ui.update { it.copy(banner = null) }

    fun switchRole(r: Role) {
        if (r !in agents) return
        switchRoleNow(r, _ui.value.tab)
        act { }
    }

    fun selectCharger(key: String) { current.selectedChargerKey = key; publish() }

    /** FR-8.2: "3번 충전기 맞나요?" → 맞아요 / 목록에서 고르기 */
    fun confirmMatch(key: String) {
        val a = current
        val m = a.match ?: return
        act { startPlan(a, key, m.pluggedAt) }
    }

    fun showAllChargers() { current.match = current.match?.copy(showAll = true); publish() }

    /** FR-1.3: 추천 시각 ±조정 (완충 ~ 완충 + 30분 / 법정 기준 안에서) */
    fun adjustDeparture(deltaMinutes: Int) {
        val p = current.plan ?: return
        val next = DepartureRules.clamp(p.selectedDepartureAt + deltaMinutes * MINUTE, p.startedAt, p.completionAt, p.powerKw, policy)
        current.plan = p.copy(selectedDepartureAt = next); publish()
    }

    fun setDepartureToMax() {
        val p = current.plan ?: return
        current.plan = p.copy(selectedDepartureAt = p.maxDepartureAt); publish()
    }

    /** FR-1.3: 확정 (한 번 터치) */
    fun confirmDeparture() {
        val a = current
        val p = a.plan ?: return
        act {
            a.backend.upsertSession(SessionRequest(p.chargerKey, p.startedAt, p.completionAt, p.selectedDepartureAt))
            a.plan = p.copy(confirmedDepartureAt = p.selectedDepartureAt)
        }
    }

    /** FR-12.3: 확정 시각 전에만, 최대 2회, 한 번에 최대 30분. 대기자에게는 서버가 알린다. */
    fun extendDeparture(minutes: Int) {
        val a = current
        val p = a.plan ?: return
        val before = p.confirmedDepartureAt ?: return
        val next = minOf(before + minutes * MINUTE, p.maxDepartureAt)
        if (next <= before) { _ui.update { it.copy(message = "완충 후 최대 ${policy.maxDepartureBufferMinutes}분까지만 정할 수 있어서 더 연장할 수 없어요.") }; return }
        DepartureRules.checkChange(before, next, clock.now(), p.extensionsUsed, false, policy)?.let { msg ->
            _ui.update { it.copy(message = msg) }; return
        }
        act {
            val view = a.backend.upsertSession(SessionRequest(p.chargerKey, p.startedAt, p.completionAt, next))
            a.plan = p.copy(confirmedDepartureAt = next, selectedDepartureAt = next, extensionsUsed = view.extensionsUsed)
        }
    }

    /** 출차를 앞당긴다 (연장 횟수에 넣지 않음). */
    fun advanceDeparture(minutes: Int) {
        val a = current
        val p = a.plan ?: return
        val before = p.confirmedDepartureAt ?: return
        val next = maxOf(p.completionAt, before - minutes * MINUTE)
        if (next == before) return
        act {
            a.backend.upsertSession(SessionRequest(p.chargerKey, p.startedAt, p.completionAt, next))
            a.plan = p.copy(confirmedDepartureAt = next, selectedDepartureAt = next)
        }
    }

    /** FR-9.3: 바뀐 완료 시각에 맞춰 출차 시각 변경 (연장 아님) */
    fun acceptRecalc() {
        val a = current
        val p = a.plan ?: return
        val pr = p.proposal ?: return
        act {
            a.backend.upsertSession(SessionRequest(p.chargerKey, p.startedAt, pr.newCompletionAt, pr.newDepartureAt, ChangeReason.RECALC))
            a.plan = p.copy(
                completionAt = pr.newCompletionAt, confirmedDepartureAt = pr.newDepartureAt, selectedDepartureAt = pr.newDepartureAt,
                maxDepartureAt = DepartureRules.maxDeparture(p.startedAt, pr.newCompletionAt, p.powerKw, policy),
                limitedByLaw = DepartureRules.limitedByLaw(p.startedAt, pr.newCompletionAt, p.powerKw, policy),
                proposal = null, note = "출차 예정을 ${hm.format(pr.newDepartureAt)}으로 바꿨어요. 대기자에게 알렸어요.",
            )
        }
    }

    /** 약속은 그대로 두고 완료 예상만 갱신한다. */
    fun keepDeparture() {
        val p = current.plan ?: return
        val pr = p.proposal ?: return
        current.plan = p.copy(completionAt = pr.newCompletionAt, proposal = null,
            note = "출차 예정 ${hm.format(p.confirmedDepartureAt ?: p.selectedDepartureAt)}을 유지해요. 완료 예상은 ${hm.format(pr.newCompletionAt)}이에요.")
        publish()
    }

    fun joinQueue(key: String) {
        val a = current
        act {
            val eta = etaOf(a)?.first
            a.backend.joinQueue(key, eta)
            a.queuedKey = key; a.lastEtaSent = eta
        }
    }

    fun leaveQueue(key: String) {
        val a = current
        act { a.backend.leaveQueue(key); if (a.queuedKey == key) a.queuedKey = null }
    }

    fun arrivalCheck(key: String, empty: Boolean) =
        act { current.backend.arrivalCheck(ArrivalCheck(key, if (empty) Observation.EMPTY else Observation.OCCUPIED)) }

    fun setUsePleos(on: Boolean) {
        _ui.update { it.copy(usePleos = on) }
        if (on) { zoneMonitor.start(); naviSource.start() } else { zoneMonitor.stop(); naviSource.stop() }
    }

    fun onLocationPermissionResult() { if (_ui.value.usePleos) zoneMonitor.start() }

    // ------------------------------------------------------------------ simulator (FR-6)

    fun setSpeed(s: Double) { clock.setSpeed(s); publish() }
    fun jumpMinutes(m: Int) { jumpTo(clock.now() + m * MINUTE) }
    private fun jumpTo(t: Long) { clock.jumpTo(t); viewModelScope.launch { runCatching { step() } } }

    fun simPlug(role: Role, plugged: Boolean) {
        val v = agents[role]?.vehicle ?: return
        if (plugged) v.plugIn() else v.unplug()
    }

    fun simLeaveZone(role: Role) = agents[role]?.vehicle?.moveTo(SampleData.HOME_LAT + 0.003, SampleData.HOME_LON + 0.002)

    fun simArriveHome(role: Role) {
        agents[role]?.simEtaAt = null
        agents[role]?.vehicle?.moveTo(SampleData.HOME_LAT, SampleData.HOME_LON, "P")
    }

    /** 시뮬레이터 내비: 집을 목적지로 설정하고 운전을 시작한다 (기어 D). */
    fun simCommute(role: Role, minutes: Int) {
        val a = agents[role] ?: return
        a.simEtaAt = clock.now() + minutes * MINUTE
        a.vehicle.moveTo(SampleData.HOME_LAT + 0.03, SampleData.HOME_LON, "D")
    }

    fun simPark(role: Role) { agents[role]?.vehicle?.update { it.copy(gear = "P") } }

    fun simSoc(role: Role, soc: Double) = agents[role]?.vehicle?.update { it.copy(socPercent = soc.coerceIn(0.0, 100.0)) }
    fun simTemp(role: Role, t: Double) = agents[role]?.vehicle?.update { it.copy(outsideTempC = t) }
    fun simTarget(role: Role, t: Int) = agents[role]?.vehicle?.update { it.copy(targetPercent = t.coerceIn(50, 100)) }
    fun simPowerFactor(role: Role, f: Double) = agents[role]?.vehicle?.update { it.copy(powerFactor = f) }

    fun resetDemo() {
        if (demo == null) {
            clock.setSpeed(1.0)
            clock.jumpTo(at(19, 0))
        }
        lastNow = clock.now()
        if (local) {
            server.reset()
            // 운전자 A의 과거 기록: 충전 완료 후 평균 10분 뒤 출차 (FR-1.2 개인 지연 보정)
            fun past(daysAgo: Int, h: Int, m: Int, delay: Int, before: Int, after: Int): HistoryEntry {
                val completion = dayStart - daysAgo * 1440 * MINUTE + (h * 60 + m) * MINUTE
                val promised = DepartureRecommender.roundUp(completion + 10 * MINUTE, policy.recommendationRoundMinutes)
                return HistoryEntry(SampleData.SCENARIO_CHARGER, completion, promised, completion + delay * MINUTE, before, after)
            }
            server.seedAccount("demo-device-a", 104, listOf(
                past(3, 22, 40, 8, 98, 100),
                past(2, 23, 20, 12, 100, 102),
                past(1, 23, 0, 10, 102, 104),
            ))
        }
        agents.values.forEach { a ->
            a.plan = null; a.match = null; a.lastPlugged = false; a.zoneCenter = null; a.lastReported = null; a.lastVerdict = null
            a.lastEventAt = 0; a.events.clear(); a.seenEvents.clear(); a.unseen = null; a.selectedChargerKey = SampleData.SCENARIO_CHARGER
            a.simEtaAt = null; a.queuedKey = null; a.lastEtaSent = null; a.wasDriving = false
        }
        agents[Role.A]?.vehicle?.update { homeVehicle(40.0, SampleData.HOME_LAT, SampleData.HOME_LON) }
        agents[Role.B]?.vehicle?.update { homeVehicle(25.0, SampleData.HOME_LAT + 0.03, SampleData.HOME_LON) }
        voice.clear()
        // 두 대 시연은 차 화면만 보여 준다 (시뮬레이터 탭 없음, 시연 조작은 Mac 감독 패널)
        _ui.update { it.copy(role = demo?.role ?: Role.A, tab = if (demo != null) Tab.HOME else Tab.SIM, scenarioIndex = 0,
            banner = null, message = null, nearby = null) }
        lastRefresh = 0
    }

    /** 기획서 4항 시나리오를 한 단계씩 진행한다 (FR-6.1). 각 단계는 앞 단계가 안 되어 있으면 대신 처리한다. */
    fun nextScenarioStep() {
        if (!local) { _ui.update { it.copy(message = "시나리오 모드는 시연(로컬) 모드에서만 동작합니다.") }; return }
        val idx = (_ui.value.scenarioIndex + 1).coerceAtMost(scenario.lastIndex)
        val a = agents.getValue(Role.A)
        val b = agents.getValue(Role.B)
        val key = SampleData.SCENARIO_CHARGER
        viewModelScope.launch {
            runCatching {
                when (idx) {
                    1 -> {
                        switchRoleNow(Role.A, Tab.HOME)
                        clock.jumpTo(at(19, 0)); lastNow = clock.now()
                        a.selectedChargerKey = key
                        a.vehicle.plugIn()
                        step()
                    }
                    2 -> { ensureAConfirmed(a); switchRoleNow(Role.A, Tab.HOME); clock.jumpTo(at(19, 10)); step() }
                    3 -> {
                        ensureAConfirmed(a)
                        clock.jumpTo(at(22, 30)); step()
                        b.simEtaAt = at(22, 58) // 내비 목적지: 집 (28분)
                        switchRoleNow(Role.B, Tab.BOARD)
                        refresh()
                        runCatching { b.backend.joinQueue(key, b.simEtaAt) }.onSuccess { b.queuedKey = key; b.lastEtaSent = b.simEtaAt }
                    }
                    4 -> {
                        ensureAConfirmed(a); ensureBQueued(b, key)
                        clock.jumpTo(at(22, 31)); switchRoleNow(Role.B, Tab.HOME)
                        b.vehicle.moveTo(SampleData.HOME_LAT + 0.03, SampleData.HOME_LON, "D")
                        step()
                    }
                    5 -> {
                        ensureAConfirmed(a); ensureBQueued(b, key)
                        if (!isDriving(b)) b.vehicle.moveTo(SampleData.HOME_LAT + 0.01, SampleData.HOME_LON, "D")
                        switchRoleNow(Role.B, Tab.HOME); clock.jumpTo(at(22, 55)); step()
                    }
                    6 -> { simArriveHome(Role.B); switchRoleNow(Role.A, Tab.HOME); clock.jumpTo(at(23, 0)); step() }
                    7 -> {
                        switchRoleNow(Role.A, Tab.HOME)
                        clock.jumpTo(at(23, 8)); step()
                        a.vehicle.unplug(); step()
                        simLeaveZone(Role.A); step()
                        switchRoleNow(Role.B, Tab.BOARD)
                    }
                    8 -> {
                        clock.jumpTo(at(23, 12)); simArriveHome(Role.B); step()
                        switchRoleNow(Role.B, Tab.BOARD)
                        b.backend.arrivalCheck(ArrivalCheck(key, Observation.EMPTY))
                    }
                    9 -> { simArriveHome(Role.A); switchRoleNow(Role.A, Tab.MANNER) }
                }
                _ui.update { it.copy(scenarioIndex = idx) }
                lastRefresh = 0; step()
            }.onFailure(::showError)
        }
    }

    /** 대체 흐름: A가 유예 시간이 지나도 출차하지 않음 → B에게 다른 충전기 안내 (FR-5.3). */
    fun runGraceExpiredBranch() {
        if (!local) return
        val a = agents.getValue(Role.A)
        val b = agents.getValue(Role.B)
        viewModelScope.launch {
            runCatching {
                ensureAPlugged(a)
                ensureAConfirmed(a)
                clock.jumpTo(at(22, 30)); step()
                ensureBQueued(b, a.plan?.chargerKey ?: SampleData.SCENARIO_CHARGER)
                val planned = a.plan?.confirmedDepartureAt ?: at(23, 10)
                clock.jumpTo(planned + (policy.graceMinutes + 1) * MINUTE); step()
                switchRoleNow(Role.B, Tab.HOME)
                lastRefresh = 0; step()
            }.onFailure(::showError)
        }
    }

    /** 대체 흐름: 충전이 예상보다 느림 → 몇 분 뒤 실제 속도로 다시 계산 → 출차 시각 변경 제안 (FR-9.3). */
    fun runSlowChargingBranch() {
        if (!local) return
        val a = agents.getValue(Role.A)
        viewModelScope.launch {
            runCatching {
                a.vehicle.update { it.copy(powerFactor = 0.7) }
                val fresh = a.plan == null
                ensureAPlugged(a)
                ensureAConfirmed(a)
                switchRoleNow(Role.A, Tab.HOME)
                val p = a.plan ?: return@runCatching
                clock.jumpTo(maxOf(clock.now(), p.startedAt) + if (fresh) 20 * MINUTE else 40 * MINUTE)
                a.plan = a.plan?.copy(lastRefineAt = p.measuredFrom)
                step()
            }.onFailure(::showError)
        }
    }

    /** 대체 흐름: 대기 1순위가 도착 예정 시각 + 10분 안에 오지 않음 → 찜 자동 취소 (FR-12.4). */
    fun runNoShowBranch() {
        if (!local) return
        val a = agents.getValue(Role.A)
        val b = agents.getValue(Role.B)
        val key = SampleData.SCENARIO_CHARGER
        viewModelScope.launch {
            runCatching {
                ensureAPlugged(a)
                ensureAConfirmed(a)
                clock.jumpTo(maxOf(clock.now(), at(22, 30))); step()
                b.simEtaAt = clock.now() + 30 * MINUTE
                ensureBQueued(b, key)
                // A가 약속대로 출차
                clock.jumpTo(maxOf(clock.now(), at(23, 8))); step()
                a.vehicle.unplug(); step(); simLeaveZone(Role.A); step()
                // B는 오지 않는다: 도착 예정 + 10분이 지나면 자동 취소
                val deadline = maxOf(clock.now(), b.simEtaAt ?: clock.now()) + (policy.queueHoldMinutes + 1) * MINUTE
                clock.jumpTo(deadline); step()
                switchRoleNow(Role.B, Tab.HOME)
                lastRefresh = 0; step()
            }.onFailure(::showError)
        }
    }

    private suspend fun ensureAPlugged(a: Agent) {
        if (a.plan != null || a.match != null) return
        clock.jumpTo(at(19, 0)); lastNow = clock.now()
        a.selectedChargerKey = SampleData.SCENARIO_CHARGER
        a.vehicle.plugIn(); step()
    }

    private suspend fun ensureAConfirmed(a: Agent) {
        a.match?.let { m -> startPlan(a, m.candidates.firstOrNull()?.chargerKey ?: a.selectedChargerKey, m.pluggedAt) }
        val p = a.plan ?: return
        if (!p.confirmed) {
            a.backend.upsertSession(SessionRequest(p.chargerKey, p.startedAt, p.completionAt, p.selectedDepartureAt))
            a.plan = p.copy(confirmedDepartureAt = p.selectedDepartureAt)
        }
    }

    private suspend fun ensureBQueued(b: Agent, key: String) {
        if (b.queuedKey == key) return
        if (b.simEtaAt == null) b.simEtaAt = clock.now() + 28 * MINUTE
        runCatching { b.backend.joinQueue(key, b.simEtaAt) }.onSuccess { b.queuedKey = key; b.lastEtaSent = b.simEtaAt }
    }

    private fun switchRoleNow(r: Role, tab: Tab) {
        _ui.update { it.copy(role = r, tab = tab, banner = null) }
        agents[r]?.let { a -> a.unseen?.let(::showBanner); a.unseen = null }
    }
}
