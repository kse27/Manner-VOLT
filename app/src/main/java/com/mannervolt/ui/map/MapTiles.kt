package com.mannervolt.ui.map

import android.content.Context
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import com.mannervolt.BuildConfig
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.MapTileIndex
import java.io.File

/**
 * 지도 타일 공급자.
 * - VWORLD_API_KEY 가 있으면 브이월드(국토교통부 공간정보 오픈플랫폼) WMTS 타일을 쓴다.
 * - 없으면 OpenStreetMap 표준 타일을 쓴다 (키 불필요, 야간은 색 반전 필터).
 * Pleos 에뮬레이터에는 Google Play 서비스가 없으므로 Google 지도 대신 타일 지도를 직접 그린다.
 */
object MapTiles {
    enum class Style(val label: String) { NIGHT("야간"), DAY("일반"), SATELLITE("위성") }

    private val vworldKey: String = BuildConfig.VWORLD_API_KEY.trim()
    val usesVWorld: Boolean get() = vworldKey.isNotEmpty()

    val styles: List<Style>
        get() = if (usesVWorld) listOf(Style.NIGHT, Style.DAY, Style.SATELLITE) else listOf(Style.NIGHT, Style.DAY)

    val providerLabel: String
        get() = if (usesVWorld) "브이월드(국토교통부) 지도" else "OpenStreetMap 지도"

    private var initialized = false

    /** MapView 를 만들기 전에 한 번 호출한다. 캐시는 앱 내부 저장소에 둔다 (저장소 권한 불필요). */
    fun init(ctx: Context) {
        if (initialized) return
        val conf = Configuration.getInstance()
        conf.load(ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        conf.userAgentValue = "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}"
        val base = File(ctx.cacheDir, "osmdroid")
        conf.osmdroidBasePath = base
        conf.osmdroidTileCache = File(base, "tiles")
        // 브이월드 인증키에 등록한 서비스 URL 이 있으면 Referer 로 보낸다.
        BuildConfig.VWORLD_DOMAIN.trim().takeIf { it.isNotEmpty() }?.let { conf.additionalHttpRequestProperties["Referer"] = it }
        initialized = true
    }

    fun source(style: Style): ITileSource =
        if (usesVWorld) when (style) {
            Style.NIGHT -> VWorldTiles("midnight", "png", 18, vworldKey)
            Style.DAY -> VWorldTiles("Base", "png", 19, vworldKey)
            Style.SATELLITE -> VWorldTiles("Satellite", "jpeg", 19, vworldKey)
        } else TileSourceFactory.MAPNIK

    /** 키 없는 OSM 타일은 밝은 지도뿐이라 야간에는 색을 반전(색상은 유지)해 어둡게 만든다. */
    fun filter(style: Style): ColorFilter? {
        if (usesVWorld || style != Style.NIGHT) return null
        val invert = ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ))
        // 반전으로 뒤집힌 색상을 180도 돌려 물·공원 색을 되살린다.
        val hue = ColorMatrix(floatArrayOf(
            -0.574f, 1.430f, 0.144f, 0f, 0f,
            0.426f, 0.430f, 0.144f, 0f, 0f,
            0.426f, 1.430f, -0.856f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ))
        // 살짝 푸른 남색 톤으로 앱 배경(#0E1216)과 맞춘다.
        val tone = ColorMatrix(floatArrayOf(
            0.85f, 0f, 0f, 0f, 6f,
            0f, 0.88f, 0f, 0f, 10f,
            0f, 0f, 0.95f, 0f, 18f,
            0f, 0f, 0f, 1f, 0f,
        ))
        return ColorMatrixColorFilter(ColorMatrix().apply { postConcat(invert); postConcat(hue); postConcat(tone) })
    }

    fun attribution(style: Style): String =
        if (usesVWorld) "지도 © 국토교통부 브이월드" else "© OpenStreetMap contributors"

    /** 브이월드 WMTS: /req/wmts/1.0.0/{key}/{layer}/{z}/{y}/{x}.{ext} (행 y, 열 x 순서) */
    private class VWorldTiles(layer: String, private val ext: String, maxZoom: Int, key: String) :
        OnlineTileSourceBase("VWorld-$layer", 6, maxZoom, 256, ".$ext", arrayOf("https://api.vworld.kr/req/wmts/1.0.0/$key/$layer/")) {
        override fun getTileURLString(pMapTileIndex: Long): String =
            "$baseUrl${MapTileIndex.getZoom(pMapTileIndex)}/${MapTileIndex.getY(pMapTileIndex)}/${MapTileIndex.getX(pMapTileIndex)}.$ext"
    }
}
