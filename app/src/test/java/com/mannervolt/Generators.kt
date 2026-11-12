package com.mannervolt

import com.mannervolt.domain.ChargeInput
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long

/** PBT-07: 도메인 제약을 지키는 생성기 */
object Gen {
    // 2020-01-01 ~ 2035-01-01
    val instant = Arb.long(1_577_836_800_000L..2_051_222_400_000L)
    val percent = Arb.int(0..100)
    val capacity = Arb.double(20.0..120.0)
    val power = Arb.double(3.0..350.0)
    val temp = Arb.double(-20.0..45.0)

    val chargeInput = arbitrary {
        ChargeInput(
            startAtMillis = instant.bind(),
            batteryCapacityKwh = capacity.bind(),
            currentPercent = percent.bind(),
            targetPercent = percent.bind(),
            chargerPowerKw = power.bind(),
            outsideTempC = temp.bind(),
            underground = Arb.boolean().bind(),
        )
    }
}
