package com.mannervolt.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.mannervolt.AppController
import com.mannervolt.Role
import com.mannervolt.Tab
import com.mannervolt.UiState

@Composable
fun MannerVoltApp(vm: AppController) {
    val s by vm.ui.collectAsState()
    MannerVoltTheme {
        Row(Modifier.fillMaxSize().background(MV.bg)) {
            NavRail(s.tab, vm::selectTab, hideSimulator = s.demoRole != null)
            Column(Modifier.fillMaxSize().padding(start = 4.dp, end = 28.dp, top = 20.dp, bottom = 20.dp)) {
                TopBar(s, vm)
                Spacer(Modifier.height(16.dp))
                Box(Modifier.fillMaxSize()) {
                    // FR-13.2: 운전 중(기어 D/R)에는 터치 요소 없는 카드 한 장만. 시뮬레이터 탭은 시연 조작용으로 남긴다.
                    val drivingView = s.driving && s.tab != Tab.SIM
                    if (drivingView) DrivingScreen(s) else when (s.tab) {
                        Tab.HOME -> HomeScreen(s, vm)
                        Tab.BOARD -> BoardScreen(s, vm)
                        Tab.NEARBY -> NearbyScreen(s)
                        Tab.MANNER -> MannerScreen(s)
                        Tab.SIM -> SimulatorScreen(s, vm)
                    }
                    if (!drivingView) Banner(s, vm)
                    if (!drivingView) s.message?.let { msg ->
                        Row(
                            Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp).widthIn(max = 1400.dp)
                                .clip(RoundedCornerShape(24.dp)).background(MV.surface2).clickable { vm.dismissMessage() }
                                .padding(horizontal = 36.dp, vertical = 24.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(msg, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(32.dp))
                            Text("닫기", style = MaterialTheme.typography.labelLarge, color = MV.volt)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavRail(selected: Tab, onSelect: (Tab) -> Unit, hideSimulator: Boolean) {
    Column(
        Modifier.fillMaxHeight().width(160.dp).padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.Bolt, null, tint = MV.volt, modifier = Modifier.size(64.dp))
        Text("매너볼트", style = MaterialTheme.typography.titleMedium, color = MV.volt)
        Spacer(Modifier.height(24.dp))
        Tab.entries.filter { !hideSimulator || it != Tab.SIM }.forEach { t ->
            val on = t == selected
            Column(
                Modifier.width(140.dp).clip(RoundedCornerShape(24.dp))
                    .background(if (on) MV.voltDim else MV.bg)
                    .clickable { onSelect(t) }.padding(vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(t.icon(), null, tint = if (on) MV.volt else MV.sub, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(8.dp))
                Text(t.title, style = MaterialTheme.typography.labelMedium, color = if (on) MV.text else MV.sub)
            }
        }
    }
}

private fun Tab.icon(): ImageVector = when (this) {
    Tab.HOME -> Icons.Filled.EvStation
    Tab.BOARD -> Icons.Filled.GridView
    Tab.NEARBY -> Icons.Filled.ViewList
    Tab.MANNER -> Icons.Filled.Star
    Tab.SIM -> Icons.Filled.Science
}

@Composable
private fun TopBar(s: UiState, vm: AppController) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(s.tab.title, style = MaterialTheme.typography.headlineLarge)
            Text(
                buildString {
                    append(if (s.demoRole != null) "두 대 시연 (공유 서버)" else if (s.remote) "서버 연결 모드" else "시연 모드 (앱 내부 서버)")
                    append(" · 차량 데이터: ")
                    append(if (s.usePleos) "Pleos Vehicle SDK" else "시뮬레이터")
                    if (s.speed != 1.0) append(" · ${s.speed.toInt()}배속")
                },
                style = MaterialTheme.typography.bodyMedium, color = MV.sub,
            )
        }
        s.demoRole?.let { r ->
            Column(
                Modifier.padding(start = 16.dp).clip(RoundedCornerShape(20.dp)).background(MV.voltDim)
                    .padding(horizontal = 28.dp, vertical = 14.dp),
            ) {
                Text(r.title, style = MaterialTheme.typography.titleMedium, color = MV.volt)
                Text(r.subtitle, style = MaterialTheme.typography.labelMedium, color = MV.sub)
            }
        }
        if (!s.remote && s.demoRole == null) {
            Role.entries.forEach { r ->
                val on = r == s.role
                Column(
                    Modifier.padding(start = 16.dp).clip(RoundedCornerShape(20.dp))
                        .background(if (on) MV.voltDim else MV.surface).clickable { vm.switchRole(r) }
                        .padding(horizontal = 28.dp, vertical = 14.dp),
                ) {
                    Text(r.title, style = MaterialTheme.typography.titleMedium, color = if (on) MV.volt else MV.text)
                    Text(r.subtitle, style = MaterialTheme.typography.labelMedium, color = MV.sub)
                }
            }
        }
        Spacer(Modifier.width(40.dp))
        Text(MV.t(s.now), style = MaterialTheme.typography.displayMedium)
    }
}

@Composable
private fun Banner(s: UiState, vm: AppController) {
    AnimatedVisibility(
        visible = s.banner != null,
        enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        val e = s.banner ?: return@AnimatedVisibility
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Row(
                Modifier.widthIn(max = 1500.dp).clip(RoundedCornerShape(28.dp)).background(MV.voltDim)
                    .clickable { vm.dismissBanner() }.padding(horizontal = 40.dp, vertical = 28.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.NotificationsActive, null, tint = MV.volt, modifier = Modifier.size(64.dp))
                Spacer(Modifier.width(28.dp))
                Column(Modifier.weight(1f, fill = false)) {
                    Text(e.title, style = MaterialTheme.typography.headlineMedium, color = MV.text)
                    Text(e.body, style = MaterialTheme.typography.titleMedium, color = MV.text)
                    e.alternatives.forEach { Text("• $it", style = MaterialTheme.typography.bodyLarge, color = MV.volt) }
                }
            }
        }
    }
}
