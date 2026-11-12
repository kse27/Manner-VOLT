package com.mannervolt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.mannervolt.AppController
import com.mannervolt.MatchState
import com.mannervolt.PlanState
import com.mannervolt.UiState
import com.mannervolt.backend.ChargerStatus
import com.mannervolt.backend.ChargerView
import com.mannervolt.backend.EventType
import com.mannervolt.domain.DepartureVerdict

@Composable
fun HomeScreen(s: UiState, vm: AppController) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.weight(1.4f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            val plan = s.plan
            val match = s.match
            when {
                match != null -> MatchCard(s, match, vm)
                plan == null -> {
                    if (s.lastVerdict == DepartureVerdict.CONFIRMED) DepartedCard(s)
                    s.arrival?.let { ArrivalCard(it) }
                    WaitingQueueCard(s)
                    IdleCard(s)
                }
                !plan.confirmed -> RecommendCard(s, plan, vm)
                else -> {
                    if (plan.proposal != null) ProposalCard(plan, vm)
                    ConfirmedCard(s, plan, vm)
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            VehicleCard(s)
            NotificationsCard(s, Modifier.weight(1f))
        }
    }
}

/** FR-8: 꽂은 시각에 '충전 중'으로 바뀐 충전기를 찾아 한 번만 확인한다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MatchCard(s: UiState, m: MatchState, vm: AppController) {
    val single = m.candidates.singleOrNull()
    when {
        single != null && !m.showAll -> Panel(title = "충전기를 꽂았어요", accent = MV.volt) {
            Text("${single.label} 충전기 맞나요?", style = MaterialTheme.typography.displayMedium)
            Text("${MV.t(m.pluggedAt)}에 '충전 중'으로 바뀐 충전기를 공공데이터에서 찾았어요. 위치(GPS)는 쓰지 않아요.",
                style = MaterialTheme.typography.titleMedium, color = MV.sub)
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                BigButton("맞아요", Modifier.weight(1f)) { vm.confirmMatch(single.chargerKey) }
                GhostButton("다른 충전기") { vm.showAllChargers() }
            }
        }
        m.candidates.size > 1 && !m.showAll -> Panel(title = "충전기를 꽂았어요", accent = MV.volt) {
            Text("같은 시각에 충전을 시작한 충전기가 ${m.candidates.size}개예요. 내 충전기를 골라 주세요.",
                style = MaterialTheme.typography.headlineMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                m.candidates.forEach { c -> ChargerChoice(c, "충전 시작 ${MV.t(c.chargingStartedAt)}") { vm.confirmMatch(c.chargerKey) } }
            }
            GhostButton("목록에 없어요") { vm.showAllChargers() }
        }
        else -> Panel(title = "어느 충전기에 꽂았나요?", accent = MV.volt) {
            Text(if (m.candidates.isEmpty()) "공공데이터에서 같은 시각에 충전을 시작한 충전기를 찾지 못했어요. 직접 골라 주세요."
                else "내 충전기를 골라 주세요.", style = MaterialTheme.typography.titleLarge)
            val choices = s.board?.chargers.orEmpty().filter { it.status != ChargerStatus.OUT_OF_SERVICE && !it.planShared }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                choices.forEach { c -> ChargerChoice(c, "${c.typeLabel} ${c.powerKw.toInt()}kW") { vm.confirmMatch(c.chargerKey) } }
            }
        }
    }
}

@Composable
private fun ChargerChoice(c: ChargerView, sub: String, onClick: () -> Unit) {
    Column(
        Modifier.clip(RoundedCornerShape(24.dp)).background(MV.surface2).clickable(onClick = onClick)
            .padding(horizontal = 36.dp, vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(c.label, style = MaterialTheme.typography.headlineLarge)
        Text(sub, style = MaterialTheme.typography.labelMedium, color = MV.sub)
    }
}

@Composable
private fun EstimateChip(p: PlanState) =
    if (p.measured) StatusChip("실제 충전 속도 기준", MV.volt) else StatusChip("추정 · 충전기 출력 기준", MV.amber)

@Composable
private fun CapText(s: UiState, p: PlanState) {
    Text(
        if (p.limitedByLaw) "법정 충전 시간 기준(급속 1시간, 완속 14시간) 때문에 ${MV.t(p.maxDepartureAt)}까지 정할 수 있어요."
        else "출차 예정은 완충 후 최대 ${s.policy.maxDepartureBufferMinutes}분(${MV.t(p.maxDepartureAt)})까지 정할 수 있어요.",
        style = MaterialTheme.typography.bodyMedium, color = MV.sub,
    )
}

/** FR-1.2/1.3 + FR-9.1 + FR-12.2: 추정 완료 시각을 바로 보여 주고, 완충 + 30분 안에서 한 번에 확정 */
@Composable
private fun RecommendCard(s: UiState, p: PlanState, vm: AppController) {
    Panel(title = "${p.chargerLabel} 충전기 · 충전을 시작했어요", accent = MV.volt) {
        Row(horizontalArrangement = Arrangement.spacedBy(64.dp)) {
            Column {
                Text("충전 완료 예상", style = MaterialTheme.typography.titleMedium, color = MV.sub)
                Text(MV.t(p.completionAt), style = MaterialTheme.typography.displayMedium)
                EstimateChip(p)
            }
            Column {
                Text("출차 예정 (추천)", style = MaterialTheme.typography.titleMedium, color = MV.sub)
                Text(MV.t(p.selectedDepartureAt), style = MaterialTheme.typography.displayLarge, color = MV.volt)
            }
        }
        Text(
            "평소 충전 완료 후 평균 ${p.personalDelayMinutes}분 뒤에 출차했어요. 추천 시각 ${MV.t(p.recommendedAt)}",
            style = MaterialTheme.typography.bodyLarge, color = MV.sub,
        )
        p.note?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MV.volt) }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            GhostButton("−5분", enabled = p.selectedDepartureAt > p.completionAt) { vm.adjustDeparture(-5) }
            GhostButton("+5분", enabled = p.selectedDepartureAt < p.maxDepartureAt) { vm.adjustDeparture(5) }
            GhostButton("최대 ${MV.t(p.maxDepartureAt)}", enabled = p.selectedDepartureAt < p.maxDepartureAt) { vm.setDepartureToMax() }
            Spacer(Modifier.weight(1f))
            BigButton("${MV.t(p.selectedDepartureAt)} 출차로 확정") { vm.confirmDeparture() }
        }
        CapText(s, p)
        Text("확정하면 충전소 배치도에 '충전 완료 예상 / 출차 예정'과 매너볼트가 공유됩니다. 차량 번호와 위치는 공유되지 않습니다.",
            style = MaterialTheme.typography.bodyMedium, color = MV.sub)
    }
}

/** FR-9.3: 확정 후 완료 예상이 바뀌면 출차 시각 변경을 묻는다 (연장 횟수에 넣지 않음). */
@Composable
private fun ProposalCard(p: PlanState, vm: AppController) {
    val pr = p.proposal ?: return
    val slower = pr.newCompletionAt > pr.oldCompletionAt
    Panel(title = if (slower) "충전이 느려져서 예상 시간이 바뀌었어요" else "충전이 빨라져서 예상 시간이 바뀌었어요", accent = MV.amber) {
        KeyValue("충전 완료 예상", "${MV.t(pr.oldCompletionAt)} → ${MV.t(pr.newCompletionAt)}", MV.amber)
        Text("출차 예정을 ${MV.t(pr.newDepartureAt)}으로 변경할까요?", style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            BigButton("${MV.t(pr.newDepartureAt)}으로 변경", Modifier.weight(1f), color = MV.amber) { vm.acceptRecalc() }
            GhostButton("${MV.t(p.confirmedDepartureAt)} 유지") { vm.keepDeparture() }
        }
        Text("충전 속도 변화에 따른 변경은 연장 횟수에 들어가지 않아요. 대기자에게는 바뀐 시각을 알려요.",
            style = MaterialTheme.typography.bodyMedium, color = MV.sub)
    }
}

@Composable
private fun ConfirmedCard(s: UiState, p: PlanState, vm: AppController) {
    val v = s.vehicle
    val planned = p.confirmedDepartureAt ?: return
    val pol = s.policy
    val canExtend = s.now < planned && p.extensionsUsed < pol.maxExtensions && planned < p.maxDepartureAt
    Panel(title = "${p.chargerLabel} 충전기 · 출차 예정 공유 중", accent = if (v?.charging == false) MV.amber else null) {
        Row(horizontalArrangement = Arrangement.spacedBy(64.dp)) {
            Column {
                Text("충전 완료 예상", style = MaterialTheme.typography.titleMedium, color = MV.sub)
                Text(MV.t(p.completionAt), style = MaterialTheme.typography.displayMedium)
                EstimateChip(p)
            }
            Column {
                Text("출차 예정", style = MaterialTheme.typography.titleMedium, color = MV.sub)
                Text(MV.t(planned), style = MaterialTheme.typography.displayLarge, color = MV.volt)
            }
        }
        p.note?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MV.volt) }
        if (v != null && !v.charging && v.plugged) {
            Text("충전이 끝났어요. 충전기를 분리하고 차량을 이동해 주세요.", style = MaterialTheme.typography.titleLarge, color = MV.amber)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("연장", style = MaterialTheme.typography.titleMedium, color = MV.sub)
            GhostButton("+10분", enabled = canExtend) { vm.extendDeparture(10) }
            GhostButton("+${pol.maxExtensionMinutes}분", enabled = canExtend) { vm.extendDeparture(pol.maxExtensionMinutes) }
            Spacer(Modifier.weight(1f))
            GhostButton("5분 앞당기기", enabled = planned > p.completionAt) { vm.advanceDeparture(5) }
        }
        Text(
            "연장 ${p.extensionsUsed}/${pol.maxExtensions}회 · 최대 ${MV.t(p.maxDepartureAt)} · 확정 시각 전에만 연장할 수 있어요. " +
                "연장하면 대기자에게 바로 알리고, 약속을 지켜도 보너스는 받지 않아요.",
            style = MaterialTheme.typography.bodyMedium, color = MV.sub,
        )
    }
    Panel(title = "실제 출차 확인 (차량 데이터)") {
        SignalRow("충전기 연결", if (v?.plugged == true) (if (v.charging) "연결됨 · 충전 중" else "연결됨 · 충전 종료") else "분리됨", ok = v?.plugged == false)
        SignalRow("기어", v?.gear ?: "-", ok = v?.gear == "D" || v?.gear == "R")
        SignalRow("충전 구역", if (s.leftZone) "벗어남" else "구역 안", ok = s.leftZone)
        KeyValue("판단", when (s.lastVerdict) {
            DepartureVerdict.SUSPECT -> "이동 감지 (세 가지가 모두 확인되면 출차)"
            DepartureVerdict.CONFIRMED -> "출차 확인"
            else -> "주차 중"
        })
        Text("충전기 분리 + 기어 D/R + 충전 구역 이탈이 모두 확인되면 출차로 판단해요. 위치는 기기 안에서 구역 이탈 여부만 계산합니다.",
            style = MaterialTheme.typography.bodyMedium, color = MV.sub)
    }
}

@Composable
private fun DepartedCard(s: UiState) {
    val last = s.profile?.history?.lastOrNull()
    Panel(title = "출차 확인 완료", accent = MV.volt) {
        Text("충전기가 '이용 가능'으로 바뀌고 대기자에게 알림이 갔어요.", style = MaterialTheme.typography.titleLarge)
        if (last != null) {
            val d = last.scoreAfter - last.scoreBefore
            KeyValue("약속 / 실제", "${MV.t(last.promisedAt)} / ${MV.t(last.actualAt)}")
            KeyValue("매너볼트", "${last.scoreBefore}V → ${last.scoreAfter}V (${if (d >= 0) "+$d" else "$d"})", if (d >= 0) MV.volt else MV.red)
        }
    }
}

/** 운전자 B: 내 대기 현황 */
@Composable
private fun WaitingQueueCard(s: UiState) {
    val mine = s.board?.chargers?.filter { it.myQueuePosition != null }.orEmpty()
    mine.forEach { c ->
        Panel(title = "찜 · ${c.label} 충전기", accent = MV.blue) {
            Row(horizontalArrangement = Arrangement.spacedBy(64.dp)) {
                Column {
                    Text("대기 순서", style = MaterialTheme.typography.titleMedium, color = MV.sub)
                    Text("${c.myQueuePosition}순위", style = MaterialTheme.typography.displayMedium, color = MV.blue)
                }
                Column {
                    Text(if (c.status == ChargerStatus.AVAILABLE) "상태" else "출차 예정", style = MaterialTheme.typography.titleMedium, color = MV.sub)
                    Text(
                        if (c.status == ChargerStatus.AVAILABLE) "이용 가능" else if (c.planShared) MV.t(c.plannedDepartureAt) else "미공유",
                        style = MaterialTheme.typography.displayMedium,
                        color = if (c.status == ChargerStatus.AVAILABLE) MV.volt else MV.text,
                    )
                }
                c.ownerScore?.let {
                    Column {
                        Text("매너볼트", style = MaterialTheme.typography.titleMedium, color = MV.sub)
                        Text("${it}V", style = MaterialTheme.typography.displayMedium)
                    }
                }
            }
            val overdue = c.status == ChargerStatus.IN_USE && c.plannedDepartureAt != null && s.now > c.plannedDepartureAt
            if (overdue) {
                val late = (s.now - c.plannedDepartureAt!!) / 60_000
                val lateText = if (late < 60) "${late}분" else "1시간 넘게"
                Text("출차 예정 시각(${MV.t(c.plannedDepartureAt)})이 $lateText 지났어요. 알림의 다른 충전기 안내를 확인하세요.",
                    style = MaterialTheme.typography.titleMedium, color = MV.amber)
            } else {
                Text("출차 ${s.policy.preNotifyMinutes}분 전, 시각이 바뀔 때, 출차가 확인될 때 차 화면으로 알려 드려요. " +
                    "자리가 비면 도착 예정 시각 + ${s.policy.queueHoldMinutes}분 안에 와야 찜이 유지돼요.",
                    style = MaterialTheme.typography.bodyLarge, color = MV.sub)
            }
        }
    }
}

@Composable
private fun IdleCard(s: UiState) {
    Panel(title = "충전기를 꽂아 주세요") {
        Text("꽂기만 하면 돼요. 같은 시각에 '충전 중'으로 바뀐 충전기를 찾아 한 번만 확인하고, 충전 완료 시각과 출차 추천 시각을 바로 계산해요.",
            style = MaterialTheme.typography.titleMedium)
        val free = s.board?.chargers.orEmpty().count { it.status == ChargerStatus.AVAILABLE }
        KeyValue("${s.board?.name ?: "이 충전소"} · 지금 이용 가능", "${free}대")
        if (s.demoRole == null && !s.remote) {
            Text("시연: 시뮬레이터 탭의 '충전기 연결' 또는 '다음 단계'를 누르세요.", style = MaterialTheme.typography.bodyMedium, color = MV.sub)
        }
    }
}

@Composable
private fun VehicleCard(s: UiState) {
    val v = s.vehicle ?: return
    Panel(title = "내 차량") {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${v.socPercent.toInt()}%", style = MaterialTheme.typography.displayMedium, color = MV.volt)
            Spacer(Modifier.width(20.dp))
            Text("목표 ${v.targetPercent}%", style = MaterialTheme.typography.titleMedium, color = MV.sub, modifier = Modifier.padding(bottom = 12.dp))
        }
        Bar((v.socPercent / 100.0).toFloat(), MV.volt)
        KeyValue("충전", if (v.charging) "충전 중 (${v.chargerPowerKw.toInt()}kW)" else if (v.plugged) "연결됨 · 충전 종료" else "연결 안 됨")
        KeyValue("외기 온도 / 위치", "${v.outsideTempC.toInt()}°C / ${if (v.underground) "지하" else "실외"}")
        KeyValue("기어", v.gear)
    }
}

@Composable
private fun NotificationsCard(s: UiState, modifier: Modifier) {
    Panel(modifier.fillMaxWidth(), title = "알림 (차 화면)") {
        if (s.events.isEmpty()) Text("아직 알림이 없어요. 차에 없는 동안 온 알림은 다음에 탈 때 보여 드려요.",
            style = MaterialTheme.typography.bodyLarge, color = MV.sub)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(s.events, key = { it.id }) { e ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MV.surface2).padding(20.dp)) {
                    Row {
                        Text(e.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                            color = if (e.type in setOf(EventType.GRACE_EXPIRED, EventType.MISMATCH_RECORDED, EventType.QUEUE_CANCELLED)) MV.amber else MV.text)
                        Text(MV.t(e.at), style = MaterialTheme.typography.labelMedium, color = MV.sub)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(e.body, style = MaterialTheme.typography.bodyMedium, color = MV.sub)
                    e.alternatives.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, color = MV.volt) }
                }
            }
        }
    }
}
