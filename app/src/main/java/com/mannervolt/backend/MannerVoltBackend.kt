package com.mannervolt.backend

/** 서버 역할 인터페이스. LocalBackend(시연)와 RemoteBackend(AWS)가 같은 계약을 따른다. */
interface MannerVoltBackend {
    val deviceId: String
    suspend fun board(stationId: String): StationBoard
    suspend fun nearby(): NearbyResult
    /** FR-8: 꽂은 시각으로 충전기 후보 찾기 */
    suspend fun match(stationId: String, pluggedAt: Long): MatchResult
    suspend fun upsertSession(req: SessionRequest): ChargerView
    suspend fun reportDeparture(report: DepartureReport): DepartureResult
    /** 이미 대기 중이면 도착 예정 시각만 갱신한다 (FR-11.2). */
    suspend fun joinQueue(chargerKey: String, etaAt: Long? = null)
    suspend fun leaveQueue(chargerKey: String)
    suspend fun arrivalCheck(check: ArrivalCheck)
    suspend fun me(): Profile
    suspend fun events(since: Long): List<AppEvent>
}
