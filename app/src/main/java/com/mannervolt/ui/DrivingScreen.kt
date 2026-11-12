package com.mannervolt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.mannervolt.ArrivalInfo
import com.mannervolt.UiState
import com.mannervolt.domain.DepartureVerdict

/** FR-11: "집 도착 22:58 · 3번 충전기 23:10 빔 (매너볼트 104V) → 도착 후 약 12분 대기" */
@Composable
fun ArrivalCard(i: ArrivalInfo, large: Boolean = false) {
    Panel(title = "퇴근길 도착 비교 · " + if (i.fromNavi) "Pleos 내비 도착 예정" else "내비 도착 예정 (시뮬레이터)", accent = MV.blue) {
        ArrivalLine(i, large)
    }
}

@Composable
private fun ArrivalLine(i: ArrivalInfo, large: Boolean) {
    val big = if (large) MaterialTheme.typography.displayLarge else MaterialTheme.typography.displayMedium
    val mid = if (large) MaterialTheme.typography.displayMedium else MaterialTheme.typography.headlineLarge
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Column {
            Text("집 도착", style = MaterialTheme.typography.titleMedium, color = MV.sub)
            Text(MV.t(i.etaAt), style = big)
        }
        Text("·", style = big, color = MV.sub)
        Column {
            Text("${i.chargerLabel} 충전기" + (i.queuePosition?.let { " · 찜 ${it}순위" } ?: ""),
                style = MaterialTheme.typography.titleMedium, color = MV.sub)
            Text(i.freeAt?.let { "${MV.t(it)} 빔" + if (i.estimated) " (추정)" else "" } ?: "지금 비어 있음",
                style = big, color = MV.volt)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        i.ownerScore?.let { StatusChip("매너볼트 ${it}V", MV.volt) }
        Text(
            "→ " + if (i.waitMinutes > 0) "도착 후 약 ${i.waitMinutes}분 대기" else "도착하면 바로 충전 가능",
            style = mid, color = if (i.waitMinutes > 0) MV.amber else MV.volt,
        )
    }
}

/**
 * FR-13.2 운전 중 화면: 터치할 요소 없이 카드 한 장만 보여 주고 새 알림은 음성으로 읽는다.
 * 기어가 P로 돌아오면 원래 화면으로 돌아간다.
 */
@Composable
fun DrivingScreen(s: UiState) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 1500.dp).fillMaxWidth().clip(RoundedCornerShape(36.dp)).background(MV.surface).padding(48.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip("운전 중 · 기어 ${s.vehicle?.gear ?: "D"}", MV.blue)
                Spacer(Modifier.width(20.dp))
                Text("화면 조작 없이 음성으로 안내해요", style = MaterialTheme.typography.titleMedium, color = MV.sub)
            }
            val arrival = s.arrival
            val latest = s.events.firstOrNull()
            when {
                arrival != null -> ArrivalLine(arrival, large = true)
                s.lastVerdict == DepartureVerdict.CONFIRMED -> {
                    Text("출차 확인", style = MaterialTheme.typography.displayLarge, color = MV.volt)
                    s.profile?.history?.lastOrNull()?.let { h ->
                        val d = h.scoreAfter - h.scoreBefore
                        Text("매너볼트 ${h.scoreAfter}V (${if (d >= 0) "+$d" else "$d"})", style = MaterialTheme.typography.displayMedium)
                    }
                }
                latest != null -> {
                    Text(latest.title, style = MaterialTheme.typography.displayMedium)
                    Text(latest.body, style = MaterialTheme.typography.headlineMedium, color = MV.sub)
                }
                else -> Text("안전 운전하세요", style = MaterialTheme.typography.displayMedium)
            }
            s.voice?.let { v ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MV.voltDim).padding(28.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = MV.volt, modifier = Modifier.size(52.dp))
                    Spacer(Modifier.width(24.dp))
                    Text("“$v”", style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}
