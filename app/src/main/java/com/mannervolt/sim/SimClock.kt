package com.mannervolt.sim

/** 시각 공급자. 시뮬레이션 모드에서는 배속과 시각 이동을 지원한다 (FR-6.2). */
interface Clock { fun now(): Long }

class SimClock(startAt: Long, private val realNow: () -> Long = System::currentTimeMillis) : Clock {
    private var baseReal = realNow()
    private var baseSim = startAt
    @Volatile var speed: Double = 1.0
        private set

    @Synchronized override fun now(): Long = baseSim + ((realNow() - baseReal) * speed).toLong()

    @Synchronized fun setSpeed(newSpeed: Double) {
        baseSim = now(); baseReal = realNow(); speed = newSpeed.coerceIn(0.0, 3600.0)
    }

    @Synchronized fun jumpTo(at: Long) { baseSim = at; baseReal = realNow() }
}
