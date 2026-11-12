package com.mannervolt.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.mannervolt.AppController
import com.mannervolt.Role
import com.mannervolt.UiState

/** FR-6: 시연용 시뮬레이터 — 시나리오 단계 실행, 시간 배속, 가상 차량 제어, Pleos SDK 상태 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SimulatorScreen(s: UiState, vm: AppController) {
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.weight(1.1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Panel(title = "기획서 4항 시나리오") {
                vm.scenario.forEachIndexed { i, step ->
                    val done = i < s.scenarioIndex
                    val now = i == s.scenarioIndex
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(44.dp).clip(CircleShape).background(if (now) MV.volt else if (done) MV.voltDim else MV.surface2),
                            contentAlignment = Alignment.Center,
                        ) { Text("$i", style = MaterialTheme.typography.labelLarge, color = if (now) MV.bg else MV.text) }
                        Spacer(Modifier.width(20.dp))
                        Column {
                            Text(step.title, style = MaterialTheme.typography.titleMedium, color = if (now) MV.volt else MV.text)
                            Text(step.detail, style = MaterialTheme.typography.bodyMedium, color = MV.sub)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    BigButton(if (s.scenarioIndex >= vm.scenario.lastIndex) "시나리오 완료" else "다음 단계 ▶", Modifier.weight(1f),
                        enabled = !s.remote && s.scenarioIndex < vm.scenario.lastIndex) { vm.nextScenarioStep() }
                    GhostButton("처음부터") { vm.resetDemo() }
                }
                Text("대체 흐름", style = MaterialTheme.typography.titleMedium, color = MV.sub)
                GhostButton("충전이 느려짐 → 실제 속도로 다시 계산 → 출차 변경 제안", Modifier.fillMaxWidth(), enabled = !s.remote) {
                    vm.runSlowChargingBranch()
                }
                GhostButton("A가 유예 시간 내 미출차 → B에게 다른 충전기 안내", Modifier.fillMaxWidth(), enabled = !s.remote) {
                    vm.runGraceExpiredBranch()
                }
                GhostButton("B가 도착 예정 + ${s.policy.queueHoldMinutes}분 안에 안 옴 → 찜 자동 취소", Modifier.fillMaxWidth(), enabled = !s.remote) {
                    vm.runNoShowBranch()
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Panel(title = "시뮬레이션 시각 ${MV.ts(s.now)}") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(1.0 to "1배", 60.0 to "60배", 600.0 to "600배").forEach { (v, l) ->
                        GhostButton(if (s.speed == v) "● $l" else l) { vm.setSpeed(v) }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    GhostButton("+5분") { vm.jumpMinutes(5) }
                    GhostButton("+15분") { vm.jumpMinutes(15) }
                    GhostButton("+1시간") { vm.jumpMinutes(60) }
                }
            }
            Role.entries.filter { !s.remote || it == Role.A }.forEach { r -> VehiclePanel(r, s, vm) }
        }
        Column(Modifier.weight(0.9f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            PleosPanel(s, vm)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VehiclePanel(r: Role, s: UiState, vm: AppController) {
    val v = if (r == s.role) s.vehicle else null
    Panel(title = "${r.title} 가상 차량" + if (r == s.role) " (현재 화면)" else "") {
        if (v != null) {
            KeyValue("배터리 / 목표", "${"%.1f".format(v.socPercent)}% / ${v.targetPercent}%")
            KeyValue("충전기", if (v.plugged) (if (v.charging) "연결 · 충전 중" else "연결 · 충전 종료") else "분리")
            KeyValue("충전 구역 / 기어", (if (s.leftZone) "벗어남" else "구역 안") + " / ${v.gear}")
            s.arrival?.let { KeyValue("내비 집 도착 예정", MV.t(it.etaAt)) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GhostButton("충전기 연결") { vm.simPlug(r, true) }
            GhostButton("충전기 분리") { vm.simPlug(r, false) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            GhostButton("구역 이탈 (기어 D)") { vm.simLeaveZone(r) }
            GhostButton("퇴근 출발 (집까지 28분)") { vm.simCommute(r, 28) }
            GhostButton("주차 (기어 P)") { vm.simPark(r) }
            GhostButton("충전소 도착") { vm.simArriveHome(r) }
        }
        if (v != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GhostButton("SoC −10") { vm.simSoc(r, v.socPercent - 10) }
                GhostButton("SoC +10") { vm.simSoc(r, v.socPercent + 10) }
                GhostButton(if (v.powerFactor < 1.0) "실제 출력 ${(v.powerFactor * 100).toInt()}% → 100%" else "실제 출력 70%로 느리게") {
                    vm.simPowerFactor(r, if (v.powerFactor < 1.0) 1.0 else 0.7)
                }
                GhostButton("외기 ${v.outsideTempC.toInt()}°C 변경") {
                    vm.simTemp(r, when { v.outsideTempC >= 30 -> -5.0; v.outsideTempC < 0 -> 8.0; v.outsideTempC < 10 -> 20.0; else -> 33.0 })
                }
            }
        }
    }
}

@Composable
private fun PleosPanel(s: UiState, vm: AppController) {
    val p = s.pleos
    Panel(title = "Pleos Vehicle SDK 2.3.2", accent = if (p.connected == true) MV.volt else null) {
        KeyValue("차량 서비스", when (p.connected) { true -> "연결됨"; false -> "연결 안 됨"; null -> "확인 중…" },
            if (p.connected == true) MV.volt else MV.amber)
        KeyValue("배터리 SoC", p.socPercent?.let { "$it%" } ?: "-")
        KeyValue("충전구 열림", p.chargePortOpen?.let { if (it) "열림" else "닫힘" } ?: "-")
        KeyValue("충전 스위치", p.chargeSwitchOn?.let { if (it) "켜짐" else "꺼짐" } ?: "-")
        KeyValue("기어", p.gear ?: "-")
        KeyValue("NaviHelper 3.0.5", when (s.naviConnected) { true -> "연결됨"; false -> "연결 안 됨"; null -> "실제 차량 모드에서 연결" },
            if (s.naviConnected == true) MV.volt else MV.sub)
        p.lastError?.let { Text("최근 오류: ${it.take(80)}", style = MaterialTheme.typography.bodyMedium, color = MV.amber) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("실제 차량 데이터 사용", style = MaterialTheme.typography.titleMedium)
                Text("운전자 A: SoC·충전구·기어는 Pleos SDK, 위치는 GPS. 운전자 B: 집 도착 예정은 Pleos 내비. 없는 값(충전 출력, 외기 온도)은 시뮬레이터 값.",
                    style = MaterialTheme.typography.bodyMedium, color = MV.sub)
            }
            Spacer(Modifier.width(16.dp))
            Switch(checked = s.usePleos, onCheckedChange = vm::setUsePleos)
        }
    }
    Panel(title = "데이터 흐름") {
        Text("• 위치 좌표는 기기 안에서만 처리하고 서버에는 '충전 구역 이탈' 여부만 보냅니다.", style = MaterialTheme.typography.bodyMedium)
        Text("• 익명 ID는 무작위 값이며 VIN·번호판을 쓰지 않습니다.", style = MaterialTheme.typography.bodyMedium)
        Text("• 공공 API 키는 서버(Secrets Manager)에만 있고 APK에는 없습니다.", style = MaterialTheme.typography.bodyMedium)
        Text("• 다른 차량은 차량 번호 없이 '충전기 번호 + 매너볼트'만 보입니다.", style = MaterialTheme.typography.bodyMedium)
        Text("• 운전 중(기어 D/R)에는 터치 없는 카드와 Pleos TTS 음성으로만 안내합니다.", style = MaterialTheme.typography.bodyMedium)
    }
}
