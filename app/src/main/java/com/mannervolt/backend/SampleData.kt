package com.mannervolt.backend

import com.mannervolt.domain.MINUTE

/** 시연용 샘플 아파트 단지 데이터 (가상의 단지, 실제 장소 아님). */
object SampleData {
    const val HOME_STATION_ID = "GV000001"
    const val HOME_LAT = 37.5000
    const val HOME_LON = 127.0300
    const val SCENARIO_CHARGER = "GV000001-03"

    data class ChargerDef(val chgerId: String, val label: String, val typeLabel: String, val powerKw: Double, val slot: Slot)

    val homeChargers = listOf(
        ChargerDef("01", "1번", "완속 AC", 7.0, Slot(0, 0)),
        ChargerDef("02", "2번", "완속 AC", 7.0, Slot(0, 1)),
        ChargerDef("03", "3번", "완속 AC", 7.0, Slot(0, 2)),
        ChargerDef("04", "4번", "완속 AC", 7.0, Slot(1, 0)),
        ChargerDef("05", "5번", "완속 AC", 11.0, Slot(1, 1)),
        ChargerDef("06", "6번", "급속 DC", 50.0, Slot(1, 2)),
    )

    /** 다른 주민 차량. shared=false 이면 매너볼트를 쓰지 않는 차량으로, 공공데이터 충전 시작 시각만 보인다 (FR-14). */
    data class Background(
        val chgerId: String, val startMin: Int, val completionMin: Int?, val departureMin: Int?, val shared: Boolean,
        val ownerScore: Int? = null,
    )

    /** 분 단위: 시나리오 당일 0시 기준 (예: 19*60 = 19:00, 24*60+30 = 다음 날 00:30) */
    val background = listOf(
        Background("01", 18 * 60 + 40, 23 * 60 + 40, 23 * 60 + 50, true, ownerScore = 112),
        Background("02", 18 * 60 + 20, null, null, false),
        Background("04", 19 * 60 + 30, 24 * 60 + 30, 24 * 60 + 40, true, ownerScore = 96),
        Background("05", 20 * 60 + 10, null, null, false),
    )
    val outOfService = setOf("06")

    fun key(statId: String, chgerId: String) = "$statId-$chgerId"

    /** 지도에 표시할 단지 충전소 위치 (좌표는 시연용). */
    val homePin = StationPin(HOME_STATION_ID, "우리 단지 지하 2층 충전소", HOME_LAT, HOME_LON)

    fun nearby(dayStart: Long): List<StationSummary> = listOf(
        StationSummary(
            "GV000002", "그린빌 아파트 지상 충전소", "샘플시 그린구 단지로 10 (지상)", "샘플충전(주)", "24시간", "120m",
            listOf(
                ChargerBrief("GV000002-01", "완속 AC", 7.0, ChargerStatus.AVAILABLE),
                ChargerBrief("GV000002-02", "완속 AC", 7.0, ChargerStatus.IN_USE, dayStart + (20 * 60) * MINUTE),
            ),
            lat = 37.5008, lon = 127.0309,
        ),
        StationSummary(
            "SP000003", "햇살공원 공영주차장", "샘플시 그린구 공원로 25", "샘플시설공단", "24시간", "850m",
            listOf(
                ChargerBrief("SP000003-01", "급속 DC", 100.0, ChargerStatus.AVAILABLE),
                ChargerBrief("SP000003-02", "급속 DC", 50.0, ChargerStatus.IN_USE, dayStart + (22 * 60 + 5) * MINUTE),
            ),
            lat = 37.5060, lon = 127.0240,
        ),
        StationSummary(
            "LB000004", "그린구립 도서관", "샘플시 그린구 책길 3", "샘플에너지", "06:00~23:00", "1.4km",
            listOf(ChargerBrief("LB000004-01", "완속 AC", 7.0, ChargerStatus.OUT_OF_SERVICE)),
            lat = 37.4910, lon = 127.0189,
        ),
    )
}
