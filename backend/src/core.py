"""매너볼트 서버 규칙. 앱의 LocalServer.kt와 같은 규칙을 적용한다 (설계 문서 6장)."""
import json
import math
import os
import time
import uuid
from datetime import datetime, timedelta, timezone

MINUTE = 60_000
KST = timezone(timedelta(hours=9))

with open(os.path.join(os.path.dirname(__file__), "policy.json"), encoding="utf-8") as f:
    POLICY = json.load(f)


class ApiError(Exception):
    def __init__(self, code: int, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


def now_ms() -> int:
    return int(time.time() * 1000)


def hm(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000, KST).strftime("%H:%M")


# ---------------- 순수 규칙 ----------------

def score_delta(late_min: int, p=POLICY, extended=False) -> int:
    """연장한 충전은 약속을 지켜도 보너스가 없다 (FR-12.3)."""
    if late_min <= p["onTimeToleranceMinutes"]:
        return 0 if extended else p["onTimeBonus"]
    steps = math.ceil((late_min - p["onTimeToleranceMinutes"]) / p["latePenaltyStepMinutes"])
    return -min(p["maxLatePenalty"], steps * p["latePenaltyPerStep"])


def apply_score(score: int, promised: int, actual: int, p=POLICY, extended=False) -> int:
    late = (actual - promised) // MINUTE
    return max(p["minScore"], min(p["maxScore"], score + score_delta(late, p, extended)))


def verdict(unplugged: bool, left_zone: bool, gear_moving: bool = False) -> str:
    """충전기 분리 + 기어 D/R + 충전 구역 이탈이 모두 있어야 출차 확정 (FR-12.1)."""
    if unplugged and gear_moving and left_zone:
        return "CONFIRMED"
    if (unplugged and gear_moving) or left_zone:
        return "SUSPECT"
    return "PARKED"


def legal_limit_min(power_kw: float, p=POLICY) -> int:
    """법정 충전 방해 기준: 급속 1시간, 완속 14시간 (법령 원문 재확인 필요)."""
    return p["legalFastLimitMinutes"] if power_kw >= p["fastChargeThresholdKw"] else p["legalSlowLimitMinutes"]


def max_departure(start: int, completion: int, power_kw: float, p=POLICY) -> int:
    """고를 수 있는 가장 늦은 출차 = min(완충 + 30분, 시작 + 법정 기준), 완충보다 이르지 않음 (FR-12.2)."""
    return max(completion, min(completion + p["maxDepartureBufferMinutes"] * MINUTE,
                               start + legal_limit_min(power_kw, p) * MINUTE))


def limited_by_law(start: int, completion: int, power_kw: float, p=POLICY) -> bool:
    return start + legal_limit_min(power_kw, p) * MINUTE < completion + p["maxDepartureBufferMinutes"] * MINUTE


def check_change(before: int, after: int, now: int, used: int, recalc: bool, p=POLICY):
    """확정 후 변경 검사 (FR-12.3). None 이면 허용. 앞당기기와 충전 속도 변화에 따른 변경은 연장이 아니다."""
    if after <= before or recalc:
        return None
    if now >= before:
        return "출차 예정 시각이 지나서 연장할 수 없어요."
    if used >= p["maxExtensions"]:
        return f"연장은 최대 {p['maxExtensions']}번까지 할 수 있어요."
    if after - before > p["maxExtensionMinutes"] * MINUTE:
        return f"한 번에 최대 {p['maxExtensionMinutes']}분까지 연장할 수 있어요."
    return None


def estimate_unshared(started: int, power_kw: float, p=POLICY) -> int:
    """매너볼트를 쓰지 않는 차량의 완료 추정 (FR-14): 공공데이터 충전 시작 + 가정 충전량 ÷ 출력."""
    if power_kw <= 0:
        return started
    hours = p["assumedSessionKwh"] / (power_kw * p["chargingEfficiency"])
    return started + math.ceil(hours * 60) * MINUTE


# ---------------- 서비스 ----------------

class Service:
    def __init__(self, store, stations, clock=now_ms, policy=POLICY):
        self.s = store
        self.stations = stations   # public_api.Stations (공공 API + 캐시 + 샘플)
        self.clock = clock
        self.p = policy

    # --- helpers
    def _profile(self, dev):
        return self.s.get_profile(dev) or {"score": self.p["initialScore"], "history": [], "queuedAt": None}

    def _emit(self, dev, typ, title, body, key=None, alternatives=None):
        at = self.clock()
        self.s.add_event(dev, {"id": str(uuid.uuid4()), "at": at, "type": typ, "title": title, "body": body,
                               "chargerKey": key, "alternatives": alternatives or []})

    def _audit(self, actor, action, target, before, after):
        self.s.add_audit({"at": self.clock(), "actor": actor, "action": action, "target": target,
                          "before": "-" if before is None else str(before), "after": "-" if after is None else str(after)})

    def _sorted_queue(self, key):
        q = self.s.list_queue(key)
        return sorted(q, key=lambda e: e["at"])

    def _check_time(self, t):
        if abs(t - self.clock()) > 10 * MINUTE:
            raise ApiError(400, "요청 시각이 올바르지 않습니다.")

    def _charger(self, key):
        c = self.stations.charger(key)
        if c is None:
            raise ApiError(404, "충전기를 찾을 수 없습니다.")
        return c

    def _rate(self, dev):
        if not self.s.rate_hit(dev, 30):
            raise ApiError(429, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.")

    # --- API
    def board(self, dev, station_id):
        st = self.stations.station(station_id)
        if st is None:
            raise ApiError(404, "충전소를 찾을 수 없습니다.")
        views = [self._view(c, dev) for c in st["chargers"]]
        return {"stationId": station_id, "name": st["name"], "underground": st["underground"], "rows": st["rows"],
                "cols": st["cols"], "chargers": views, "dataAsOf": st["dataAsOf"], "source": st["source"]}

    def _view(self, c, dev):
        key = c["chargerKey"]
        s = self.s.get_session(key)
        state = self.s.get_state(key) or {}
        q = self._sorted_queue(key)
        pos = next((i + 1 for i, e in enumerate(q) if e["deviceId"] == dev), None)
        if c["publicStatus"] == "OUT_OF_SERVICE":
            status = "OUT_OF_SERVICE"
        elif s is not None or c["publicStatus"] == "IN_USE":
            status = "IN_USE"
        else:
            status = c["publicStatus"]
        started = s["startedAt"] if s else c.get("nowChargingStartedAt")
        mine = bool(s and s["owner"] == dev)
        return {
            "chargerKey": key, "label": c["label"], "typeLabel": c["typeLabel"], "powerKw": c["powerKw"],
            "status": status, "slot": c["slot"],
            "chargingStartedAt": started,
            "predictedCompletionAt": s["completion"] if s else None,
            "plannedDepartureAt": s["planned"] if s else None,
            "planShared": s is not None,
            "queueSize": len(q), "myQueuePosition": pos, "isMine": mine,
            "departure": s.get("verdict", "PARKED") if s else "PARKED",
            "awaitingArrivalCheck": bool(state.get("awaiting")),
            # FR-13.1: 차량 번호 없이 매너볼트만 / FR-14: 약속 없는 차량은 공공데이터 기준 추정만
            "ownerScore": self._profile(s["owner"])["score"] if s else None,
            "estimatedCompletionAt": estimate_unshared(started, c["powerKw"], self.p) if (s is None and status == "IN_USE" and started) else None,
            "extensionsUsed": s.get("extensions", 0) if mine else 0,
        }

    def nearby(self):
        return self.stations.nearby()

    def match(self, dev, station_id, plugged_at):
        """FR-8: 꽂은 시각 ± N분 안에 공공데이터 상태가 '충전 중'이 된(nowTsdt), 약속 없는 충전기. 가까운 시각 순."""
        self._rate(dev)
        st = self.stations.station(station_id)
        if st is None:
            raise ApiError(404, "충전소를 찾을 수 없습니다.")
        window = self.p["matchWindowMinutes"] * MINUTE
        found = [c for c in st["chargers"]
                 if c["publicStatus"] == "IN_USE" and c.get("nowChargingStartedAt")
                 and abs(c["nowChargingStartedAt"] - plugged_at) <= window and self.s.get_session(c["chargerKey"]) is None]
        found.sort(key=lambda c: abs(c["nowChargingStartedAt"] - plugged_at))
        return {"candidates": [self._view(c, dev) for c in found], "windowMinutes": self.p["matchWindowMinutes"]}

    def upsert_session(self, dev, key, started, completion, planned, reason="USER"):
        self._rate(dev)
        c = self._charger(key)
        now = self.clock()
        if started > now + 10 * MINUTE or completion < started or planned < completion:
            raise ApiError(400, "시각 값이 올바르지 않습니다.")
        if planned - started > 48 * 60 * MINUTE:
            raise ApiError(400, "시각 값이 올바르지 않습니다.")
        cap = max_departure(started, completion, c["powerKw"], self.p)
        if planned > cap:
            raise ApiError(400, f"법정 충전 시간 기준 때문에 {hm(cap)}까지만 정할 수 있어요."
                           if limited_by_law(started, completion, c["powerKw"], self.p)
                           else f"출차 예정은 완충 후 최대 {self.p['maxDepartureBufferMinutes']}분({hm(cap)})까지 정할 수 있어요.")
        recalc = reason == "RECALC"
        s = self.s.get_session(key)
        if c["publicStatus"] == "OUT_OF_SERVICE":
            raise ApiError(409, "운영 중지된 충전기입니다.")
        if s is None:
            prof = self._profile(dev)
            if prof.get("activeSession"):
                raise ApiError(409, "이미 다른 충전기를 사용 중입니다.")
            new = {"owner": dev, "startedAt": started, "completion": completion, "planned": planned, "changes": 0,
                   "extensions": 0, "verdict": "PARKED", "preNotified": False, "completeNotified": False, "graceNotified": False}
            if not self.s.create_session(key, new):
                raise ApiError(409, "다른 차량이 사용 중인 충전기입니다.")
            self.s.put_state(key, {"awaiting": False, "emptyReports": []})
            self.s.delete_queue(key, dev)
            prof["activeSession"] = key
            if prof.get("queuedAt") == key:
                prof["queuedAt"] = None
            self.s.put_profile(dev, prof)
            self._audit(dev, "SESSION_START", key, None, hm(planned))
        elif s["owner"] != dev:
            raise ApiError(403, "다른 차량이 사용 중인 충전기입니다.")
        else:
            before = s["planned"]
            if before != planned:
                err = check_change(before, planned, now, s.get("extensions", 0), recalc, self.p)
                if err:
                    raise ApiError(409, err)
                if s["changes"] >= self.p["maxDepartureChangesPerSession"]:
                    raise ApiError(429, "출차 시각 변경 횟수를 넘었습니다.")
            s["completion"] = completion
            if before != planned:
                extension = not recalc and planned > before
                s["planned"] = planned
                s["changes"] += 1
                s["preNotified"] = False
                s["graceNotified"] = False
                if extension:
                    s["extensions"] = s.get("extensions", 0) + 1
                action = "DEPARTURE_EXTEND" if extension else "DEPARTURE_RECALC" if recalc else "DEPARTURE_CHANGE"
                self._audit(dev, action, key, hm(before), hm(planned))
                change = f"{hm(before)} → {hm(planned)}"
                for e in self.s.list_queue(key):
                    if extension:
                        self._emit(e["deviceId"], "DEPARTURE_CHANGED", f"{c['label']} 출차 {(planned - before) // MINUTE}분 연장",
                                   f"{change} (연장 {s['extensions']}/{self.p['maxExtensions']}회)", key)
                    elif recalc:
                        self._emit(e["deviceId"], "DEPARTURE_CHANGED", f"{c['label']} 출차 예정 변경",
                                   f"충전 속도가 바뀌어 {change} 으로 조정됐습니다.", key)
                    else:
                        self._emit(e["deviceId"], "DEPARTURE_CHANGED", f"{c['label']} 출차 시각 변경",
                                   f"{change} 으로 바뀌었습니다.", key)
            self.s.put_session(key, s)
        return self._view(c, dev)

    def report_departure(self, dev, key, charging_ended, unplugged, left_zone, observed, gear_moving=False):
        self._rate(dev)
        c = self._charger(key)
        s = self.s.get_session(key)
        if s is None:
            raise ApiError(404, "진행 중인 충전이 없습니다.")
        if s["owner"] != dev:
            raise ApiError(403, "권한이 없습니다.")
        self._check_time(observed)
        v = verdict(unplugged, left_zone, gear_moving)
        before = self._profile(dev)["score"]
        s["verdict"] = v
        if v != "CONFIRMED":
            self.s.put_session(key, s)
            return {"verdict": v, "scoreBefore": before, "scoreAfter": before}
        self._confirm(c, s, dev, observed)
        return {"verdict": v, "scoreBefore": before, "scoreAfter": self._profile(dev)["score"]}

    def _confirm(self, c, s, actor, at):
        key = c["chargerKey"]
        owner = s["owner"]
        prof = self._profile(owner)
        before = prof["score"]
        extended = s.get("extensions", 0) > 0
        prof["score"] = apply_score(before, s["planned"], at, self.p, extended)
        prof["history"] = (prof["history"] + [{"chargerKey": key, "completionAt": s["completion"], "promisedAt": s["planned"],
                                               "actualAt": at, "scoreBefore": before, "scoreAfter": prof["score"]}])[-20:]
        prof["activeSession"] = None
        self.s.put_profile(owner, prof)
        self._audit(actor, "SCORE", owner, before, prof["score"])
        late = (at - s["planned"]) // MINUTE
        d = prof["score"] - before
        if late > self.p["onTimeToleranceMinutes"]:
            body = f"약속보다 {late}분 늦었습니다. 현재 {prof['score']}V"
        elif extended:
            body = f"약속한 {hm(s['planned'])}을 지켰습니다. 연장한 충전이라 보너스는 없어요. 현재 {prof['score']}V"
        else:
            body = f"약속한 {hm(s['planned'])}을 지켰습니다. 현재 {prof['score']}V"
        self._emit(owner, "SCORE_CHANGED", f"매너볼트 {'+' if d >= 0 else ''}{d}", body, key)
        self._audit(actor, "DEPARTURE_CONFIRMED", key, "IN_USE", "AVAILABLE")
        self.s.delete_session(key)
        self.s.put_state(key, {"awaiting": True, "emptyReports": [], "availableAt": at})
        for i, e in enumerate(self._sorted_queue(key)):
            if i == 0:
                body = f"출차가 확인되었습니다. {hm(self._hold_deadline(at, e))}까지 도착하면 자리를 맡아 둬요. 도착하면 자리 상태를 눌러 주세요."
            else:
                body = f"출차가 확인되었습니다. 대기 {i + 1}순위입니다."
            self._emit(e["deviceId"], "CHARGER_AVAILABLE", f"{c['label']} 충전기 이용 가능", body, key)

    def _hold_deadline(self, freed_at, entry):
        """대기 1순위 자리 유지: 도착 예정(없으면 빈 시각) + N분 (FR-12.4)."""
        return max(freed_at, entry.get("etaAt") or freed_at) + self.p["queueHoldMinutes"] * MINUTE

    def join_queue(self, dev, key, eta=None):
        """이미 대기 중이면 도착 예정 시각만 갱신한다 (FR-11.2)."""
        self._rate(dev)
        c = self._charger(key)
        q = self.s.list_queue(key)
        mine = next((e for e in q if e["deviceId"] == dev), None)
        if mine is not None:
            if eta is not None:
                mine["etaAt"] = eta
                self.s.put_queue(key, dev, mine)
            return
        prof = self._profile(dev)
        s = self.s.get_session(key)
        status = self._view(c, dev)["status"]
        q = self.s.list_queue(key)
        if status != "IN_USE":
            raise ApiError(409, "사용 중인 충전기에만 대기 등록할 수 있습니다.")
        if s and s["owner"] == dev:
            raise ApiError(409, "내 차량이 사용 중인 충전기입니다.")
        if prof.get("queuedAt"):
            raise ApiError(409, "다른 충전기에 이미 대기 중입니다.")
        if len(q) >= self.p["waitlistMax"]:
            raise ApiError(409, "대기 인원이 가득 찼습니다.")
        self.s.put_queue(key, dev, {"deviceId": dev, "at": self.clock(), "etaAt": eta})
        prof["queuedAt"] = key
        self.s.put_profile(dev, prof)
        self._audit(dev, "QUEUE_JOIN", key, None, eta)

    def leave_queue(self, dev, key):
        self._rate(dev)
        self._charger(key)
        self.s.delete_queue(key, dev)
        prof = self._profile(dev)
        if prof.get("queuedAt") == key:
            prof["queuedAt"] = None
            self.s.put_profile(dev, prof)
        self._audit(dev, "QUEUE_LEAVE", key, None, None)

    def arrival_check(self, dev, key, observed):
        self._rate(dev)
        c = self._charger(key)
        s = self.s.get_session(key)
        state = self.s.get_state(key) or {"awaiting": False, "emptyReports": []}
        if s is None and observed == "EMPTY":
            if state.get("awaiting"):
                state["awaiting"] = False
                state["availableAt"] = None
                self.s.put_state(key, state)
                self.leave_queue(dev, key)
                self._audit(dev, "ARRIVAL_CONFIRMED", key, "AVAILABLE", "EMPTY")
                self._emit(dev, "ARRIVAL_CONFIRMED", f"{c['label']} 기록 확정", "자리가 비어 있음을 확인했습니다. 충전기를 연결해 주세요.", key)
        elif s is None:
            self._audit(dev, "MISMATCH", key, "AVAILABLE", "OCCUPIED")
            self._emit(dev, "MISMATCH_RECORDED", "불일치 기록", "차량 데이터는 출차로 판단했지만 자리가 차 있다고 알려 주셨습니다. 기록해 두었습니다.", key)
        elif observed == "EMPTY":
            if s["owner"] == dev:
                raise ApiError(409, "내 차량의 충전기입니다.")
            reports = set(state.get("emptyReports", [])) | {dev}
            state["emptyReports"] = sorted(reports)
            self.s.put_state(key, state)
            self._audit(dev, "MISMATCH", key, "IN_USE", "EMPTY")
            if len(reports) >= self.p["emptyReportsToOverride"]:
                self._confirm(c, s, "reports", self.clock())
            else:
                self._emit(dev, "MISMATCH_RECORDED", "불일치 기록", "차량 데이터로는 아직 출차 전입니다. 신고를 기록했습니다.", key)

    def me(self, dev):
        p = self._profile(dev)
        return {"deviceId": dev, "score": p["score"], "history": p["history"]}

    def events(self, dev, since):
        return self.s.events_since(dev, since)

    def ensure_profile(self, dev):
        if self.s.get_profile(dev) is None:
            self.s.put_profile(dev, {"score": self.p["initialScore"], "history": [], "queuedAt": None})

    # --- Sweeper (EventBridge 1분 주기)
    def _hold_expiry(self, key, now):
        """FR-12.4: 빈 충전기의 대기 1순위가 기한 안에 오지 않으면 찜을 취소하고 다음 순번에게 넘긴다."""
        state = self.s.get_state(key) or {}
        freed = state.get("availableAt")
        if not state.get("awaiting") or not freed or self.s.get_session(key) is not None:
            return
        q = self._sorted_queue(key)
        if not q:
            return
        first = q[0]
        deadline = self._hold_deadline(freed, first)
        if now <= deadline:
            return
        c = self.stations.charger(key)
        label = c["label"] if c else key
        self.s.delete_queue(key, first["deviceId"])
        prof = self._profile(first["deviceId"])
        if prof.get("queuedAt") == key:
            prof["queuedAt"] = None
            self.s.put_profile(first["deviceId"], prof)
        self._audit("system", "QUEUE_EXPIRED", key, first["deviceId"], None)
        self._emit(first["deviceId"], "QUEUE_CANCELLED", f"{label} 찜 자동 취소",
                   f"도착 예정 시각({hm(deadline)})이 지나 찜이 취소됐어요. 다음 순번에게 넘어갑니다.", key)
        state["availableAt"] = now
        self.s.put_state(key, state)
        if len(q) > 1:
            nxt = q[1]
            self._emit(nxt["deviceId"], "CHARGER_AVAILABLE", f"{label} 충전기 이용 가능",
                       f"앞 순번이 오지 않아 1순위가 되었어요. {hm(self._hold_deadline(now, nxt))}까지 도착해 주세요.", key)

    def tick(self):
        now = self.clock()
        for key in self.s.awaiting_keys():
            self._hold_expiry(key, now)
        for key in self.s.active_keys():
            s = self.s.get_session(key)
            c = self.stations.charger(key)
            if s is None or c is None:
                continue
            changed = False
            if not s["completeNotified"] and now >= s["completion"]:
                s["completeNotified"] = changed = True
                self._emit(s["owner"], "CHARGE_COMPLETE", "충전 완료", f"출차 예정 {hm(s['planned'])}입니다. 충전기를 분리하고 이동해 주세요.", key)
            if not s["preNotified"] and now >= s["planned"] - self.p["preNotifyMinutes"] * MINUTE:
                s["preNotified"] = changed = True
                for e in self.s.list_queue(key):
                    self._emit(e["deviceId"], "PRE_DEPARTURE", f"{c['label']} 곧 비어요",
                               f"출차 예정 {hm(s['planned'])} ({self.p['preNotifyMinutes']}분 전 알림)", key)
            if not s["graceNotified"] and now >= s["planned"] + self.p["graceMinutes"] * MINUTE:
                s["graceNotified"] = changed = True
                q = self._sorted_queue(key)
                if q:
                    self._emit(q[0]["deviceId"], "GRACE_EXPIRED", f"{c['label']} 출차 지연",
                               f"유예 {self.p['graceMinutes']}분이 지나도 출차가 확인되지 않았습니다. 가까운 다른 충전기를 안내합니다.",
                               key, self.stations.alternatives(key))
            if changed:
                self.s.put_session(key, s)
