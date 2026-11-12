package com.mannervolt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.mannervolt.UiState
import com.mannervolt.backend.ChargerStatus
import com.mannervolt.backend.DataSource
import com.mannervolt.ui.map.MapTiles
import com.mannervolt.ui.map.StationMap
import com.mannervolt.ui.map.distanceLabel
import com.mannervolt.ui.map.stationColor

/** FR-2.2, FR-7.3: 주변 충전소 지도 + 목록 (공공 API 정보 + 데이터 기준 시각) */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NearbyScreen(s: UiState) {
    val n = s.nearby ?: run { Text("불러오는 중…", style = MaterialTheme.typography.titleLarge); return }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(selected) {
        val idx = n.stations.indexOfFirst { it.statId == selected }
        if (idx >= 0) listState.animateScrollToItem(idx)
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(
            "데이터 기준 ${MV.tf(n.dataAsOf)} · " + when (n.source) {
                DataSource.LIVE -> "한국환경공단 충전소 정보 (실시간)"
                DataSource.CACHE -> "한국환경공단 충전소 정보 (캐시: 최근 호출 실패 또는 제한)"
                DataSource.SAMPLE -> "샘플 데이터 (서버 연결 시 한국환경공단 공공 API 사용)"
            } + " · ${MapTiles.providerLabel}",
            style = MaterialTheme.typography.bodyLarge, color = MV.sub,
        )
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            StationMap(
                home = n.home,
                stations = n.stations,
                selectedId = selected,
                onSelect = { selected = it },
                modifier = Modifier.weight(1.45f).fillMaxHeight().clip(RoundedCornerShape(28.dp)).background(MV.surface),
            )
            LazyColumn(Modifier.weight(1f).fillMaxHeight(), state = listState, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                itemsIndexed(n.stations, key = { _, st -> st.statId }) { _, st ->
                    val isSel = st.statId == selected
                    Panel(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).clickable { selected = st.statId },
                        accent = if (isSel) MV.volt else null,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(18.dp).clip(CircleShape).background(stationColor(st)))
                            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                                Text(st.name, style = MaterialTheme.typography.headlineMedium)
                                Text("${st.address} · ${st.operator} · ${st.useTime}", style = MaterialTheme.typography.bodyMedium, color = MV.sub)
                            }
                            val dist = distanceLabel(st, n.home)
                            if (dist.isNotBlank()) Text(dist, style = MaterialTheme.typography.headlineMedium, color = MV.volt)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            val avail = st.chargers.count { it.status == ChargerStatus.AVAILABLE }
                            StatusChip("이용 가능 $avail / ${st.chargers.size}", if (avail > 0) MV.volt else MV.gray)
                            if (st.lat == null || st.lon == null) StatusChip("위치 정보 없음", MV.gray)
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            st.chargers.take(6).forEach { c ->
                                Column(Modifier.clip(RoundedCornerShape(18.dp)).background(MV.surface2).padding(horizontal = 24.dp, vertical = 16.dp)) {
                                    Text("${c.chargerKey.substringAfterLast('-')}번 · ${c.typeLabel} ${c.outputKw.toInt()}kW", style = MaterialTheme.typography.titleMedium)
                                    Text(MV.statusLabel(c.status), style = MaterialTheme.typography.labelMedium, color = MV.statusColor(c.status))
                                    c.nowChargingStartedAt?.let {
                                        Text("충전 시작 ${MV.t(it)} · 예정 시각 미공유", style = MaterialTheme.typography.labelMedium, color = MV.sub)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
