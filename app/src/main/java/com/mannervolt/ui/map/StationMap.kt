package com.mannervolt.ui.map

import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mannervolt.backend.ChargerStatus
import com.mannervolt.backend.StationPin
import com.mannervolt.backend.StationSummary
import com.mannervolt.domain.Geo
import com.mannervolt.ui.MV
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import kotlin.math.max

private const val LABEL_ZOOM = 17.0

/** 충전소 상태 요약 색: 빈 충전기 있음 > 사용 중 > 운영 중지 > 미확인 */
fun stationColor(st: StationSummary): Color = when {
    st.chargers.any { it.status == ChargerStatus.AVAILABLE } -> MV.volt
    st.chargers.any { it.status == ChargerStatus.IN_USE } -> MV.amber
    st.chargers.isNotEmpty() && st.chargers.all { it.status == ChargerStatus.OUT_OF_SERVICE } -> MV.red
    else -> MV.gray
}

/** 서버가 거리를 주지 않으면(공공 API) 단지 좌표와 충전소 좌표로 계산한다. */
fun distanceLabel(st: StationSummary, home: StationPin?): String {
    if (st.distanceLabel.isNotBlank()) return st.distanceLabel
    if (home == null || st.lat == null || st.lon == null) return ""
    val m = Geo.distanceMeters(home.lat, home.lon, st.lat, st.lon)
    return if (m < 1000) "${(m / 10).toInt() * 10}m" else String.format("%.1fkm", m / 1000)
}

/**
 * 실제 지도 위에 단지 충전소와 주변 충전소를 표시한다.
 * 마커를 누르면 [onSelect] 로 statId 를 알리고, [selectedId] 가 바뀌면 그 위치로 이동한다.
 */
@Composable
fun StationMap(
    home: StationPin?,
    stations: List<StationSummary>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var style by rememberSaveable { mutableStateOf(MapTiles.Style.NIGHT) }
    var fitTick by remember { mutableIntStateOf(0) }
    val select by rememberUpdatedState(onSelect)
    // 충전소가 많으면 멀리서 볼 때는 핀만, 확대하면 이름표까지 보여 준다 (선택한 곳은 항상 이름표).
    val zoomedIn = remember { mutableStateOf(false) }

    val mapView = remember {
        MapTiles.init(ctx)
        MapView(ctx).apply {
            setTileSource(MapTiles.source(style))
            overlayManager.tilesOverlay.setColorFilter(MapTiles.filter(style))
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isHorizontalMapRepetitionEnabled = false
            isVerticalMapRepetitionEnabled = false
            minZoomLevel = 7.0
            maxZoomLevel = 19.0
            isTilesScaledToDpi = true
            controller.setZoom(15.0)
            home?.let { controller.setCenter(GeoPoint(it.lat, it.lon)) }
            addMapListener(object : MapListener {
                override fun onScroll(event: ScrollEvent?) = false
                override fun onZoom(event: ZoomEvent?): Boolean {
                    zoomedIn.value = (event?.zoomLevel ?: zoomLevelDouble) >= LABEL_ZOOM
                    return false
                }
            })
        }
    }

    DisposableEffect(lifecycle, mapView) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            mapView.onPause()
            mapView.onDetach()
        }
    }

    LaunchedEffect(style) {
        mapView.setTileSource(MapTiles.source(style))
        mapView.overlayManager.tilesOverlay.setColorFilter(MapTiles.filter(style))
        mapView.invalidate()
    }

    // 좌표 목록 (좌표가 바뀔 때만 다시 맞춘다)
    val points = remember(home, stations) {
        buildList {
            home?.let { add(GeoPoint(it.lat, it.lon)) }
            stations.forEach { s -> if (s.lat != null && s.lon != null) add(GeoPoint(s.lat, s.lon)) }
        }
    }
    val coordKey = points.joinToString(";") { "${it.latitude},${it.longitude}" }
    LaunchedEffect(coordKey, fitTick) { if (points.isNotEmpty()) fitAll(mapView, points, animated = fitTick > 0) }

    // 마커 다시 그리기 (선택된 마커는 크게, 맨 위에)
    val allLabels = stations.count { it.lat != null && it.lon != null } <= 4 || zoomedIn.value
    LaunchedEffect(home, stations, selectedId, allLabels) {
        mapView.overlays.removeAll { it is Marker }
        val ordered = stations.filter { it.lat != null && it.lon != null }.sortedBy { it.statId == selectedId }
        ordered.forEach { st ->
            val avail = st.chargers.count { it.status == ChargerStatus.AVAILABLE }
            val color = stationColor(st).toArgb()
            val dist = distanceLabel(st, home)
            val sub = "이용 가능 $avail/${st.chargers.size}" + if (dist.isNotBlank()) " · $dist" else ""
            val sel = st.statId == selectedId
            val pin = PinFactory.make(ctx, avail.toString(), color, st.name, sub, color, sel, showLabel = allLabels || sel)
            mapView.overlays.add(marker(mapView, GeoPoint(st.lat!!, st.lon!!), pin) { select(st.statId) })
        }
        home?.let { h ->
            val pin = PinFactory.make(ctx, null, MV.blue.toArgb(), h.name, "내 차 충전 위치", MV.blue.toArgb(), h.statId == selectedId, home = true,
                showLabel = allLabels || h.statId == selectedId)
            val m = marker(mapView, GeoPoint(h.lat, h.lon), pin) { select(h.statId) }
            if (h.statId == selectedId) mapView.overlays.add(m) else mapView.overlays.add(0, m)
        }
        mapView.invalidate()
    }

    // 목록이나 마커에서 고르면 그 위치로 이동
    LaunchedEffect(selectedId) {
        val target = when (selectedId) {
            null -> null
            home?.statId -> GeoPoint(home.lat, home.lon)
            else -> stations.firstOrNull { it.statId == selectedId }?.takeIf { it.lat != null && it.lon != null }
                ?.let { GeoPoint(it.lat!!, it.lon!!) }
        }
        target?.let { mapView.controller.animateTo(it, max(mapView.zoomLevelDouble, 16.0), 700L) }
    }

    Box(modifier) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        // 지도 스타일 전환
        Row(
            Modifier.align(Alignment.TopStart).padding(20.dp)
                .clip(RoundedCornerShape(22.dp)).background(MV.surface.copy(alpha = 0.92f)).padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MapTiles.styles.forEach { st ->
                val on = st == style
                Text(
                    st.label,
                    modifier = Modifier.clip(RoundedCornerShape(18.dp))
                        .background(if (on) MV.volt else Color.Transparent)
                        .clickable { style = st }
                        .padding(horizontal = 26.dp, vertical = 14.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (on) Color(0xFF071A10) else MV.text,
                )
            }
        }

        // 확대/축소, 전체 보기, 내 단지
        Column(Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 48.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MapButton(Icons.Filled.Add) { mapView.controller.zoomIn() }
            MapButton(Icons.Filled.Remove) { mapView.controller.zoomOut() }
            MapButton(Icons.Filled.ZoomOutMap) { fitTick++ }
            if (home != null) MapButton(Icons.Filled.MyLocation) {
                if (selectedId == home.statId) mapView.controller.animateTo(GeoPoint(home.lat, home.lon), 16.5, 700L)
                else select(home.statId)
            }
        }

        // 범례
        Row(
            Modifier.align(Alignment.BottomStart).padding(20.dp)
                .clip(RoundedCornerShape(18.dp)).background(MV.surface.copy(alpha = 0.92f))
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Legend(MV.blue, "내 단지")
            Legend(MV.volt, "이용 가능")
            Legend(MV.amber, "모두 사용 중")
            Legend(MV.red, "운영 중지")
            Legend(MV.gray, "상태 미확인")
        }

        // 지도 출처 표기
        Text(
            MapTiles.attribution(style),
            modifier = Modifier.align(Alignment.BottomEnd)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(topStart = 12.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFCFD8E0),
        )

        if (points.isEmpty()) {
            Text(
                "좌표 정보가 있는 충전소가 없어요",
                modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(18.dp))
                    .background(MV.surface.copy(alpha = 0.92f)).padding(24.dp),
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}

private fun marker(map: MapView, p: GeoPoint, pin: PinFactory.Pin, onClick: () -> Unit) = Marker(map).apply {
    position = p
    icon = BitmapDrawable(map.resources, pin.bitmap)
    setAnchor(pin.anchorU, pin.anchorV)
    setInfoWindow(null)
    setOnMarkerClickListener { _, _ -> onClick(); true }
}

private fun fitAll(map: MapView, points: List<GeoPoint>, animated: Boolean) {
    val run = Runnable {
        if (points.size == 1) {
            map.controller.animateTo(points[0], 16.0, if (animated) 700L else 0L)
        } else {
            val n = points.maxOf { it.latitude }; val s = points.minOf { it.latitude }
            val e = points.maxOf { it.longitude }; val w = points.minOf { it.longitude }
            val dLat = (n - s).coerceAtLeast(0.002); val dLon = (e - w).coerceAtLeast(0.002)
            // 핀 몸통은 위쪽, 이름표는 오른쪽으로 그려지므로 그 방향 여백을 더 둔다. 위·아래 구석의 버튼/범례도 피한다.
            val box = BoundingBox(n + dLat * 0.22, e + dLon * 0.85, s - dLat * 0.12, w - dLon * 0.12)
            map.zoomToBoundingBox(box, animated, 60, 16.5, if (animated) 700L else null)
        }
    }
    if (map.width > 0 && map.height > 0) run.run() else map.addOnFirstLayoutListener { _, _, _, _, _ -> run.run() }
}

@Composable
private fun MapButton(icon: ImageVector, onClick: () -> Unit) {
    Box(
        Modifier.size(76.dp).clip(RoundedCornerShape(22.dp)).background(MV.surface.copy(alpha = 0.92f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = MV.text, modifier = Modifier.size(38.dp)) }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(16.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MV.text)
    }
}
