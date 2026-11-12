package com.mannervolt.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.max

/**
 * 지도 마커 그림.
 * - 충전소: 원형 핀(가운데 빈 충전기 수) + 오른쪽 이름표, 앵커는 핀 꼬리 끝.
 * - 내 단지(home): 꼬리 없는 원(번개) + 왼쪽 이름표, 앵커는 원 중심. 가까운 충전소 핀과 겹치지 않게 한다.
 */
internal object PinFactory {
    class Pin(val bitmap: Bitmap, val anchorU: Float, val anchorV: Float)

    private const val TEXT = 0xFFECF1F5.toInt()
    private const val INK = 0xFF071A10.toInt()
    private const val LABEL_BG = 0xEB171D23.toInt()
    private const val LINE = 0xFF2E3843.toInt()
    private const val VOLT = 0xFF3BE28A.toInt()

    /** center == null 이면 번개 모양을 그린다 (내 단지). */
    fun make(ctx: Context, center: String?, color: Int, title: String, sub: String, subColor: Int, selected: Boolean, home: Boolean = false, showLabel: Boolean = true): Pin {
        val d = ctx.resources.displayMetrics.density
        val m = 8f * d                                // 그림자 여백
        val r = (if (selected) 34f else if (home) 24f else 27f) * d   // 핀 반지름
        val ring = 4f * d
        val tail = if (home) 0f else 14f * d
        val gap = if (showLabel) 10f * d else 0f
        val padH = 16f * d
        val padV = 10f * d

        val titleP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = (if (selected) 24f else 21f) * d; this.color = TEXT; typeface = Typeface.DEFAULT_BOLD
        }
        val subP = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = (if (selected) 19f else 17f) * d; this.color = subColor }
        val labelW = if (showLabel) max(titleP.measureText(title), subP.measureText(sub)) + padH * 2 else 0f
        val labelH = if (showLabel) titleP.textSize + subP.textSize + 6f * d + padV * 2 else 0f

        val cx = if (home) m + labelW + gap + r + ring else m + r + ring
        val cy = m + max(r + ring, labelH / 2)
        val tipY = cy + r + ring + tail
        val w = (m + 2 * (r + ring) + gap + labelW + m).toInt()
        val h = (max(tipY, cy + labelH / 2) + m).toInt()

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val shadow = 0x66000000

        // 핀 꼬리 + 흰 테두리 + 색 원
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xFFFFFFFF.toInt(); setShadowLayer(6f * d, 0f, 2f * d, shadow) }
        val tailPath = Path().apply {
            moveTo(cx - r * 0.55f, cy + r * 0.6f); lineTo(cx + r * 0.55f, cy + r * 0.6f); lineTo(cx, tipY); close()
        }
        if (home) {
            // 내 단지: 바깥에 옅은 파란 후광
            c.drawCircle(cx, cy, r + ring + 10f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = (color and 0x00FFFFFF) or 0x40000000 })
        } else {
            c.drawPath(tailPath, white)
        }
        c.drawCircle(cx, cy, r + ring, white)
        c.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })

        if (center != null) {
            val cp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = INK; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
                textSize = r * (if (center.length > 2) 0.7f else 0.95f)
            }
            c.drawText(center, cx, cy - (cp.ascent() + cp.descent()) / 2, cp)
        } else {
            drawBolt(c, cx, cy, r * 1.25f, INK)
        }

        if (!showLabel) return Pin(bmp, cx / w, (if (home) cy else tipY) / h)

        // 이름표
        val lx = if (home) m else cx + r + ring + gap
        val rect = RectF(lx, cy - labelH / 2, lx + labelW, cy + labelH / 2)
        c.drawRoundRect(rect, 16f * d, 16f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = LABEL_BG; setShadowLayer(6f * d, 0f, 2f * d, shadow)
        })
        c.drawRoundRect(rect, 16f * d, 16f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = (if (selected) 3f else 1.5f) * d; this.color = if (selected) VOLT else LINE
        })
        val titleBase = rect.top + padV - titleP.ascent()
        c.drawText(title, lx + padH, titleBase, titleP)
        c.drawText(sub, lx + padH, titleBase + titleP.descent() + 6f * d - subP.ascent(), subP)

        return Pin(bmp, cx / w, (if (home) cy else tipY) / h)
    }

    /** Material 'bolt' (24 격자) 모양 */
    private fun drawBolt(c: Canvas, cx: Float, cy: Float, size: Float, color: Int) {
        val s = size / 24f
        val pts = floatArrayOf(13f, 2f, 3f, 14f, 12f, 14f, 11f, 22f, 21f, 10f, 12f, 10f)
        val p = Path()
        for (i in pts.indices step 2) {
            val x = cx + (pts[i] - 12f) * s
            val y = cy + (pts[i + 1] - 12f) * s
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        c.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    }
}
