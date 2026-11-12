package com.mannervolt.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mannervolt.UiState
import com.mannervolt.domain.DelaySample
import com.mannervolt.domain.DepartureRecommender
import com.mannervolt.domain.MINUTE

/** FR-5: 매너볼트 신뢰도 */
@Composable
fun MannerScreen(s: UiState) {
    val p = s.profile ?: run { Text("불러오는 중…", style = MaterialTheme.typography.titleLarge); return }
    val pol = s.policy
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Panel(title = "${s.role.title}의 매너볼트", accent = MV.volt) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${p.score}V", style = MaterialTheme.typography.displayLarge, color = MV.volt)
                    Spacer(Modifier.width(16.dp))
                    Text("/ ${pol.maxScore}V", style = MaterialTheme.typography.headlineMedium, color = MV.sub, modifier = Modifier.padding(bottom = 16.dp))
                }
                Bar(p.score / pol.maxScore.toFloat(), MV.volt)
                val delay = DepartureRecommender.personalDelayMinutes(p.history.map { DelaySample(it.completionAt, it.actualAt) }, pol)
                KeyValue("출차 추천에 쓰는 개인 지연", "${delay}분" + if (p.history.isEmpty()) " (기본값)" else " (최근 ${minOf(p.history.size, pol.delayHistoryWindow)}건 평균)")
            }
            Panel(title = "규칙 (config/policy.json)") {
                Text("• 출차 확인: 충전기 분리 + 기어 D/R + 충전 구역 이탈", style = MaterialTheme.typography.bodyLarge)
                Text("• 약속 시각 +${pol.onTimeToleranceMinutes}분 안에 출차: +${pol.onTimeBonus}V (연장한 충전은 보너스 없음)", style = MaterialTheme.typography.bodyLarge)
                Text("• 늦으면 ${pol.latePenaltyStepMinutes}분마다 −${pol.latePenaltyPerStep}V (최대 −${pol.maxLatePenalty}V)", style = MaterialTheme.typography.bodyLarge)
                Text("• 출차 예정은 완충 후 최대 ${pol.maxDepartureBufferMinutes}분까지, 연장은 확정 시각 전 최대 ${pol.maxExtensions}회(회당 ${pol.maxExtensionMinutes}분)", style = MaterialTheme.typography.bodyLarge)
                Text("• 유예 ${pol.graceMinutes}분이 지나도 출차하지 않으면 대기자에게 다른 충전기 안내", style = MaterialTheme.typography.bodyLarge)
            }
        }
        Panel(Modifier.weight(1.2f).fillMaxHeight(), title = "출차 기록") {
            if (p.history.isEmpty()) Text("아직 기록이 없어요.", style = MaterialTheme.typography.bodyLarge, color = MV.sub)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(p.history.reversed()) { h ->
                    val late = Math.floorDiv(h.actualAt - h.promisedAt, MINUTE)
                    val d = h.scoreAfter - h.scoreBefore
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(MV.tf(h.actualAt), style = MaterialTheme.typography.titleMedium)
                            Text("충전 완료 ${MV.t(h.completionAt)} · 약속 ${MV.t(h.promisedAt)} · 실제 ${MV.t(h.actualAt)}",
                                style = MaterialTheme.typography.bodyMedium, color = MV.sub)
                        }
                        Text(if (late <= 0) "정시" else "${late}분 늦음", style = MaterialTheme.typography.titleMedium,
                            color = if (late <= pol.onTimeToleranceMinutes) MV.volt else MV.amber)
                        Spacer(Modifier.width(32.dp))
                        Text(if (d >= 0) "+$d" else "$d", style = MaterialTheme.typography.headlineMedium,
                            color = if (d >= 0) MV.volt else MV.red)
                    }
                    HorizontalDivider(color = MV.line)
                }
            }
        }
    }
}

