package com.mannervolt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.mannervolt.AppController
import com.mannervolt.UiState
import com.mannervolt.backend.ChargerStatus
import com.mannervolt.backend.ChargerView
import com.mannervolt.backend.DataSource
import com.mannervolt.domain.DepartureVerdict

/** FR-2: 충전 종료 예정 공유 지도 (단지 배치도) */
@Composable
fun BoardScreen(s: UiState, vm: AppController) {
    val board = s.board ?: run { Text("불러오는 중…", style = MaterialTheme.typography.titleLarge); return }
    var selectedKey by remember { mutableStateOf<String?>(null) }
    val selected = board.chargers.firstOrNull { it.chargerKey == selectedKey }
        ?: board.chargers.firstOrNull { it.myQueuePosition != null || it.isMine }

    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.weight(1.7f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(board.name, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                Text(asOf(board.source, board.dataAsOf), style = MaterialTheme.typography.bodyMedium, color = MV.sub)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                listOf(ChargerStatus.AVAILABLE, ChargerStatus.IN_USE, ChargerStatus.OUT_OF_SERVICE).forEach {
                    StatusChip(MV.statusLabel(it), MV.statusColor(it))
                }
            }
            // 배치도: 주차면 격자 + 가운데 통로
            Column(
                Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(28.dp)).background(MV.surface).padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                for (row in 0 until board.rows) {
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        for (col in 0 until board.cols) {
                            val c = board.chargers.firstOrNull { it.slot.row == row && it.slot.col == col }
                            Box(Modifier.weight(1f).fillMaxHeight()) {
                                if (c != null) ChargerTile(c, c.chargerKey == selected?.chargerKey) { selectedKey = c.chargerKey }
                            }
                        }
                    }
                    if (row == 0 && board.rows > 1) {
                        Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp)).background(MV.bg), contentAlignment = Alignment.Center) {
                            Text("← 차량 통로 →", style = MaterialTheme.typography.labelMedium, color = MV.gray)
                        }
                    }
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            if (selected == null) {
                Panel(title = "충전기를 선택하세요") {
                    Text("곧 비는 충전기를 눌러 출차 예정 시각을 보고 대기 등록을 할 수 있어요.", style = MaterialTheme.typography.titleMedium)
                }
            } else ChargerDetail(s, selected, vm)
        }
    }
}

private fun asOf(src: DataSource, at: Long) = "데이터 기준 ${MV.tf(at)} · " + when (src) {
    DataSource.LIVE -> "공공 API 실시간"
    DataSource.CACHE -> "캐시 (공공 API 응답 지연)"
    DataSource.SAMPLE -> "샘플 단지 데이터"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChargerTile(c: ChargerView, selected: Boolean, onClick: () -> Unit) {
    val color = MV.statusColor(c.status)
    Column(
        Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)).background(MV.surface2)
            .border(if (selected) 4.dp else 2.dp, if (selected) MV.text else color.copy(alpha = 0.6f), RoundedCornerShape(24.dp))
            .clickable(onClick = onClick).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.label, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.weight(1f))
            StatusChip(MV.statusLabel(c.status), color)
        }
        Text("${c.typeLabel} ${c.powerKw.toInt()}kW", style = MaterialTheme.typography.bodyMedium, color = MV.sub)
        Spacer(Modifier.weight(1f))
        when {
            c.status == ChargerStatus.IN_USE && c.planShared -> {
                Text("충전 완료 예상 ${MV.t(c.predictedCompletionAt)}", style = MaterialTheme.typography.titleMedium)
                Text("출차 예정 ${MV.t(c.plannedDepartureAt)}", style = MaterialTheme.typography.headlineMedium, color = MV.volt)
            }
            // FR-14: 매너볼트를 쓰지 않는 차량은 공공데이터 기준 추정만, 매너볼트 없음
            c.status == ChargerStatus.IN_USE -> {
                Text("충전 시작 ${MV.t(c.chargingStartedAt)}", style = MaterialTheme.typography.titleMedium)
                Text("완료 ${MV.t(c.estimatedCompletionAt)} (추정)", style = MaterialTheme.typography.titleMedium, color = MV.gray)
            }
            c.status == ChargerStatus.AVAILABLE && c.awaitingArrivalCheck ->
                Text("방금 출차 확인됨", style = MaterialTheme.typography.titleMedium, color = MV.volt)
            else -> Unit
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (c.isMine) StatusChip("내 차량", MV.blue)
            c.ownerScore?.let { if (!c.isMine) StatusChip("매너볼트 ${it}V", MV.volt) }
            if (c.status == ChargerStatus.IN_USE && !c.planShared) StatusChip("추정 · 매너볼트 미사용", MV.gray)
            if (c.queueSize > 0) StatusChip("대기 ${c.queueSize}명", MV.sub)
            c.myQueuePosition?.let { StatusChip("내 순서 $it", MV.blue) }
            if (c.departure == DepartureVerdict.SUSPECT) StatusChip("이동 감지", MV.amber)
        }
    }
}

@Composable
private fun ChargerDetail(s: UiState, c: ChargerView, vm: AppController) {
    Panel(title = "${c.label} 충전기", accent = MV.statusColor(c.status)) {
        KeyValue("상태", MV.statusLabel(c.status), MV.statusColor(c.status))
        KeyValue("종류 / 출력", "${c.typeLabel} / ${c.powerKw.toInt()}kW")
        KeyValue("충전기 ID", c.chargerKey)
        if (c.status == ChargerStatus.IN_USE) {
            KeyValue("충전 시작", MV.t(c.chargingStartedAt))
            if (c.planShared) {
                KeyValue("충전 완료 예상", MV.t(c.predictedCompletionAt))
                KeyValue("출차 예정", MV.t(c.plannedDepartureAt), MV.volt)
                c.ownerScore?.let { KeyValue("매너볼트", "${it}V", MV.volt) }
            } else {
                KeyValue("완료 추정", "${MV.t(c.estimatedCompletionAt)} (공공데이터 기준)", MV.gray)
                KeyValue("출차 예정", "약속 없음 (매너볼트 미사용 차량)", MV.gray)
            }
        }
        KeyValue("대기", "${c.queueSize} / ${s.policy.waitlistMax}명")
    }
    if (c.status == ChargerStatus.IN_USE && !c.isMine) {
        Panel(title = "찜 (대기 등록)") {
            if (c.myQueuePosition != null) {
                Text("${c.myQueuePosition}순위로 대기 중이에요.", style = MaterialTheme.typography.titleLarge, color = MV.blue)
                GhostButton("대기 취소", Modifier.fillMaxWidth()) { vm.leaveQueue(c.chargerKey) }
            } else {
                s.arrival?.let { Text("집 도착 예정 ${MV.t(it.etaAt)}", style = MaterialTheme.typography.titleMedium, color = MV.blue) }
                BigButton("찜하기 (대기 등록)", Modifier.fillMaxWidth(), enabled = c.queueSize < s.policy.waitlistMax) { vm.joinQueue(c.chargerKey) }
                Text(
                    "찜은 한 곳만 할 수 있어요. 도착 예정 시각을 함께 보내고, 자리가 비면 도착 예정 + ${s.policy.queueHoldMinutes}분 안에 와야 유지돼요.",
                    style = MaterialTheme.typography.bodyMedium, color = MV.sub,
                )
            }
        }
    }
    // FR-4.3: 도착한 대기자의 자리 확인
    if (!c.isMine && c.status != ChargerStatus.OUT_OF_SERVICE &&
        (c.awaitingArrivalCheck || c.myQueuePosition != null || c.status == ChargerStatus.IN_USE)
    ) {
        Panel(title = "도착했나요? 자리 상태를 알려 주세요") {
            Text("차량 데이터와 대조해 판단 오류를 줄여요.", style = MaterialTheme.typography.bodyLarge, color = MV.sub)
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                BigButton("자리 비어 있음", Modifier.weight(1f)) { vm.arrivalCheck(c.chargerKey, true) }
                BigButton("아직 있음", Modifier.weight(1f), color = MV.amber) { vm.arrivalCheck(c.chargerKey, false) }
            }
        }
    }
    if (c.status == ChargerStatus.AVAILABLE) {
        Panel(title = "이 충전기로 충전") {
            GhostButton(if (s.selectedChargerKey == c.chargerKey) "연결할 충전기로 선택됨" else "연결할 충전기로 선택", Modifier.fillMaxWidth()) {
                vm.selectCharger(c.chargerKey)
            }
        }
    }
    Spacer(Modifier.padding(4.dp))
}
