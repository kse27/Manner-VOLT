package com.mannervolt

import com.mannervolt.backend.AppEvent
import com.mannervolt.backend.ChargerStatus
import com.mannervolt.backend.ChargerView
import com.mannervolt.backend.DataSource
import com.mannervolt.backend.EventType
import com.mannervolt.backend.HistoryEntry
import com.mannervolt.backend.Profile
import com.mannervolt.backend.SessionRequest
import com.mannervolt.backend.Slot
import com.mannervolt.backend.StationBoard
import com.mannervolt.backend.Wire
import com.mannervolt.domain.DepartureVerdict
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.enum
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** PBT-02: 앱-서버 DTO의 JSON 직렬화 왕복 */
class SerializationRoundTripTest {
    private val cfg = PropTestConfig(iterations = 300)

    private val chargerView = arbitrary {
        ChargerView(
            chargerKey = Arb.string(1..24).bind(), label = Arb.string(0..8).bind(), typeLabel = Arb.string(0..8).bind(),
            powerKw = Arb.double(3.0..350.0).bind(), status = Arb.enum<ChargerStatus>().bind(),
            slot = Slot(Arb.int(0..5).bind(), Arb.int(0..5).bind()),
            chargingStartedAt = Gen.instant.orNull().bind(), predictedCompletionAt = Gen.instant.orNull().bind(),
            plannedDepartureAt = Gen.instant.orNull().bind(), planShared = Arb.boolean().bind(),
            queueSize = Arb.int(0..3).bind(), myQueuePosition = Arb.int(1..3).orNull().bind(), isMine = Arb.boolean().bind(),
            departure = Arb.enum<DepartureVerdict>().bind(), awaitingArrivalCheck = Arb.boolean().bind(),
        )
    }

    @Test fun stationBoardRoundTrip() = runBlocking<Unit> {
        val board = arbitrary {
            StationBoard(Arb.string(1..12).bind(), Arb.string(0..30).bind(), Arb.boolean().bind(), 2, 3,
                Arb.list(chargerView, 0..6).bind(), Gen.instant.bind(), Arb.enum<DataSource>().bind())
        }
        checkAll(cfg, board) { b ->
            assertEquals(b, Wire.json.decodeFromString(StationBoard.serializer(), Wire.json.encodeToString(StationBoard.serializer(), b)))
        }
    }

    @Test fun eventAndProfileRoundTrip() = runBlocking<Unit> {
        val event = arbitrary {
            AppEvent(Arb.string(1..36).bind(), Gen.instant.bind(), Arb.enum<EventType>().bind(), Arb.string().bind(),
                Arb.string().bind(), Arb.string(1..20).orNull().bind(), Arb.list(Arb.string(), 0..3).bind())
        }
        val profile = arbitrary {
            Profile(Arb.string(1..36).bind(), Arb.int(0..150).bind(),
                Arb.list(arbitrary { HistoryEntry(Arb.string(1..20).bind(), Gen.instant.bind(), Gen.instant.bind(), Gen.instant.bind(), Arb.int(0..150).bind(), Arb.int(0..150).bind()) }, 0..5).bind())
        }
        checkAll(cfg, event, profile) { e, p ->
            assertEquals(e, Wire.json.decodeFromString(AppEvent.serializer(), Wire.json.encodeToString(AppEvent.serializer(), e)))
            assertEquals(p, Wire.json.decodeFromString(Profile.serializer(), Wire.json.encodeToString(Profile.serializer(), p)))
        }
    }

    @Test fun sessionRequestRoundTrip() = runBlocking<Unit> {
        checkAll(cfg, Gen.instant, Gen.instant, Gen.instant) { a, b, c ->
            val r = SessionRequest("GV000001-03", a, b, c)
            assertEquals(r, Wire.json.decodeFromString(SessionRequest.serializer(), Wire.json.encodeToString(SessionRequest.serializer(), r)))
        }
    }
}
