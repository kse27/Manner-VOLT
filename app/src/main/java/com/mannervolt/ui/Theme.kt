package com.mannervolt.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mannervolt.backend.ChargerStatus
import java.text.SimpleDateFormat
import java.util.Locale

object MV {
    val bg = Color(0xFF0E1216)
    val surface = Color(0xFF171D23)
    val surface2 = Color(0xFF212932)
    val line = Color(0xFF2E3843)
    val text = Color(0xFFECF1F5)
    val sub = Color(0xFF9AA7B4)
    val volt = Color(0xFF3BE28A)      // 매너볼트 그린
    val voltDim = Color(0xFF1C5E3C)
    val amber = Color(0xFFFFB547)
    val red = Color(0xFFFF6B6B)
    val blue = Color(0xFF5AB4FF)
    val gray = Color(0xFF6B7682)

    fun statusColor(s: ChargerStatus) = when (s) {
        ChargerStatus.AVAILABLE -> volt
        ChargerStatus.IN_USE -> amber
        ChargerStatus.OUT_OF_SERVICE -> red
        ChargerStatus.UNKNOWN -> gray
    }

    fun statusLabel(s: ChargerStatus) = when (s) {
        ChargerStatus.AVAILABLE -> "이용 가능"
        ChargerStatus.IN_USE -> "사용 중"
        ChargerStatus.OUT_OF_SERVICE -> "운영 중지"
        ChargerStatus.UNKNOWN -> "상태 미확인"
    }

    private val hm = SimpleDateFormat("HH:mm", Locale.KOREA)
    private val hms = SimpleDateFormat("HH:mm:ss", Locale.KOREA)
    private val full = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA)
    fun t(ms: Long?): String = ms?.let { hm.format(it) } ?: "--:--"
    fun ts(ms: Long): String = hms.format(ms)
    fun tf(ms: Long): String = full.format(ms)
}

@Composable
fun MannerVoltTheme(content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = MV.volt, onPrimary = Color(0xFF04210F), secondary = MV.blue,
        background = MV.bg, surface = MV.surface, onSurface = MV.text, onBackground = MV.text,
        surfaceVariant = MV.surface2, onSurfaceVariant = MV.sub, error = MV.red, outline = MV.line,
    )
    val type = Typography(
        displayLarge = TextStyle(fontSize = 76.sp, fontWeight = FontWeight.Bold),
        displayMedium = TextStyle(fontSize = 54.sp, fontWeight = FontWeight.Bold),
        headlineLarge = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.Bold),
        headlineMedium = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = TextStyle(fontSize = 25.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Medium),
        bodyLarge = TextStyle(fontSize = 20.sp),
        bodyMedium = TextStyle(fontSize = 18.sp),
        labelLarge = TextStyle(fontSize = 21.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
    )
    MaterialTheme(colorScheme = colors, typography = type) {
        CompositionLocalProvider(LocalContentColor provides MV.text, content = content)
    }
}
