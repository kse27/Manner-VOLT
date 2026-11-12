"""두 대 시연용 로컬 서버 (영상 촬영용).

- 에뮬레이터 두 대(A 차량, B 차량)가 같은 상태를 보도록 Mac에서 띄운다. 에뮬레이터에서는 http://10.0.2.2:8787 로 접속한다.
- 규칙은 AWS 백엔드와 같은 backend/src/core.py 를 그대로 쓰고, 저장소는 메모리(MemoryStore)를 쓴다.
- 공공 API 대신 앱 SampleData.kt와 같은 가상 단지 데이터를 쓴다. API 키가 필요 없다.
- 시뮬레이션 시각을 서버가 관리해 두 화면의 시계가 같게 움직인다.
- 감독 패널(http://127.0.0.1:8787/director)에서 시나리오를 진행한다. 차 화면에는 시연 조작 버튼이 나오지 않는다.
- 127.0.0.1 에만 열린다. 로그인은 없고, 시연 기기 ID 두 개만 받는다.

실행: python3 demo/demo_server.py  (또는 demo/run-two-cars.sh)
"""
import json
import os
import sys
import threading
import time
from datetime import datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
sys.path.insert(0, os.path.join(ROOT, "backend", "src"))

import validate as V  # noqa: E402
from core import KST, MINUTE, ApiError, Service, POLICY  # noqa: E402
from store import MemoryStore  # noqa: E402

HOST, PORT = "127.0.0.1", int(os.environ.get("DEMO_PORT", "8787"))
STATION = "GV000001"
KEY = "GV000001-03"
DEVICES = {"demo-device-a": "A", "demo-device-b": "B"}
DEVICE_OF = {v: k for k, v in DEVICES.items()}


def day_start():
    now = datetime.now(KST)
    return int(datetime(now.year, now.month, now.day, tzinfo=KST).timestamp() * 1000)


DAY = day_start()


def at(h, m):
    return DAY + (h * 60 + m) * MINUTE


# ---------------------------------------------------------------- 시계

class DemoClock:
    """서버가 관리하는 시뮬레이션 시각. 배속과 시각 이동을 지원한다."""

    def __init__(self, start):
        self.lock = threading.Lock()
        self.base_sim, self.base_real, self.speed = start, time.time() * 1000, 1.0

    def __call__(self):
        with self.lock:
            return int(self.base_sim + (time.time() * 1000 - self.base_real) * self.speed)

    def jump(self, t):
        with self.lock:
            self.base_sim, self.base_real = t, time.time() * 1000

    def set_speed(self, s):
        now = self()
        with self.lock:
            self.base_sim, self.base_real, self.speed = now, time.time() * 1000, max(0.0, min(3600.0, s))


# ---------------------------------------------------------------- 가상 단지 (앱 SampleData.kt와 같음)

CHARGERS = [
    ("01", "1번", "완속 AC", 7.0, 0, 0), ("02", "2번", "완속 AC", 7.0, 0, 1), ("03", "3번", "완속 AC", 7.0, 0, 2),
    ("04", "4번", "완속 AC", 7.0, 1, 0), ("05", "5번", "완속 AC", 11.0, 1, 1), ("06", "6번", "급속 DC", 50.0, 1, 2),
]
OUT_OF_SERVICE = {"06"}


class DemoStations:
    """공공데이터 층: 차가 실제로 꽂힌 시각(nowTsdt 역할)을 시연 중에 바꿀 수 있다."""

    def __init__(self, clock):
        self.clock = clock
        self.physical = {}

    def _chargers(self):
        out = []
        for cid, label, typ, kw, r, c in CHARGERS:
            key = f"{STATION}-{cid}"
            started = self.physical.get(key)
            status = "OUT_OF_SERVICE" if cid in OUT_OF_SERVICE else ("IN_USE" if started else "AVAILABLE")
            out.append({"chargerKey": key, "label": label, "typeLabel": typ, "powerKw": kw, "slot": {"row": r, "col": c},
                        "publicStatus": status, "nowChargingStartedAt": started})
        return out

    def station(self, station_id):
        if station_id != STATION:
            return None
        return {"name": "그린빌 아파트 지하 2층 충전소", "underground": True, "rows": 2, "cols": 3,
                "chargers": self._chargers(), "dataAsOf": self.clock(), "source": "SAMPLE"}

    def charger(self, key):
        return next((c for c in self._chargers() if c["chargerKey"] == key), None)

    def nearby(self):
        st = [
            {"statId": "GV000002", "name": "그린빌 아파트 지상 충전소", "address": "샘플시 그린구 단지로 10 (지상)", "operator": "샘플충전(주)",
             "useTime": "24시간", "distanceLabel": "120m", "lat": 37.5008, "lon": 127.0309, "chargers": [
                 {"chargerKey": "GV000002-01", "typeLabel": "완속 AC", "outputKw": 7.0, "status": "AVAILABLE", "nowChargingStartedAt": None},
                 {"chargerKey": "GV000002-02", "typeLabel": "완속 AC", "outputKw": 7.0, "status": "IN_USE", "nowChargingStartedAt": at(20, 0)}]},
            {"statId": "SP000003", "name": "햇살공원 공영주차장", "address": "샘플시 그린구 공원로 25", "operator": "샘플시설공단",
             "useTime": "24시간", "distanceLabel": "850m", "lat": 37.5060, "lon": 127.0240, "chargers": [
                 {"chargerKey": "SP000003-01", "typeLabel": "급속 DC", "outputKw": 100.0, "status": "AVAILABLE", "nowChargingStartedAt": None},
                 {"chargerKey": "SP000003-02", "typeLabel": "급속 DC", "outputKw": 50.0, "status": "IN_USE", "nowChargingStartedAt": at(22, 5)}]},
            {"statId": "LB000004", "name": "그린구립 도서관", "address": "샘플시 그린구 책길 3", "operator": "샘플에너지",
             "useTime": "06:00~23:00", "distanceLabel": "1.4km", "lat": 37.4910, "lon": 127.0189, "chargers": [
                 {"chargerKey": "LB000004-01", "typeLabel": "완속 AC", "outputKw": 7.0, "status": "OUT_OF_SERVICE", "nowChargingStartedAt": None}]},
        ]
        # 좌표는 앱 SampleData.kt와 같은 시연용 값 (지도 핀 표시)
        return {"stations": st, "dataAsOf": self.clock(), "source": "SAMPLE",
                "home": {"statId": STATION, "name": "우리 단지 지하 2층 충전소", "lat": 37.5000, "lon": 127.0300}}

    def alternatives(self, exclude_key):
        here = [f"같은 단지 {c['label']} ({c['typeLabel']})" for c in self._chargers()
                if c["chargerKey"] != exclude_key and c["publicStatus"] == "AVAILABLE"]
        near = [f"{s['name']} {s['distanceLabel']} ({c['typeLabel']} {int(c['outputKw'])}kW)"
                for s in self.nearby()["stations"] for c in s["chargers"] if c["status"] == "AVAILABLE"]
        return (here + near)[:3]


# ---------------------------------------------------------------- 시연 상태

STEPS = [
    ("준비", "19:00, 운전자 A가 지하 2층 충전소에 도착"),
    ("① A 충전기 연결", "꽂으면 \"3번 충전기 맞나요?\" → A 화면에서 '맞아요', '출차로 확정'을 직접 터치"),
    ("①' 19:10 실제 충전 속도로 확인", "예상 그대로 23:00"),
    ("② 22:30 B 출발 전 3번 찜", "B 배치도에서 3번(23:10 빔, 104V) 찜, 도착 예정 22:58 전송"),
    ("②' 22:31 B 퇴근길 운전 중", "B: 터치 없는 카드 + 음성 안내"),
    ("②'' 22:55 사전 알림", "운전 중인 B에게 음성 알림"),
    ("③ 23:00 충전 완료", "A에게 출차 안내 (B는 22:58 도착)"),
    ("③' 23:08 A 출차", "분리 + 기어 D + 구역 이탈 → B 화면에 '3번 이용 가능'"),
    ("③'' 23:12 B 도착 확인", "B '자리 비어 있음' → 기록 확정"),
    ("④ 매너볼트 반영", "A: 104V → 106V"),
]


class Demo:
    def __init__(self):
        self.lock = threading.RLock()
        self.clock = DemoClock(at(19, 0))
        self.step = 0
        self.busy = False
        self.commands = {"A": [], "B": []}
        self.seq = 0
        self.reset()

    def reset(self):
        with self.lock:
            self.clock.set_speed(1.0)
            self.clock.jump(at(19, 0))
            self.store = MemoryStore()
            self.stations = DemoStations(self.clock)
            self.svc = Service(self.store, self.stations, clock=self.clock)
            # 다른 주민 차량: 01, 04는 매너볼트로 약속 공유, 02, 05는 매너볼트 미사용(공공데이터만)
            for cid, start in (("01", at(18, 40)), ("02", at(18, 20)), ("04", at(19, 30)), ("05", at(20, 10))):
                self.stations.physical[f"{STATION}-{cid}"] = start
            for cid, start, comp, planned, score in (("01", at(18, 40), at(23, 40), at(23, 50), 112),
                                                     ("04", at(19, 30), at(24, 30), at(24, 40), 96)):
                owner = f"resident-{cid}"
                self.store.put_profile(owner, {"score": score, "history": [], "queuedAt": None, "activeSession": f"{STATION}-{cid}"})
                self.store.create_session(f"{STATION}-{cid}", {
                    "owner": owner, "startedAt": start, "completion": comp, "planned": planned, "changes": 0, "extensions": 0,
                    "verdict": "PARKED", "preNotified": False, "completeNotified": False, "graceNotified": False})
            # 운전자 A의 과거 기록: 충전 완료 후 평균 10분 뒤 출차 (앱 시연 모드와 같음)
            hist = []
            for days, h, m, delay, before, after in ((3, 22, 40, 8, 98, 100), (2, 23, 20, 12, 100, 102), (1, 23, 0, 10, 102, 104)):
                comp = DAY - days * 1440 * MINUTE + (h * 60 + m) * MINUTE
                promised = comp + 10 * MINUTE
                promised += (-promised) % (5 * MINUTE)
                hist.append({"chargerKey": KEY, "completionAt": comp, "promisedAt": promised, "actualAt": comp + delay * MINUTE,
                             "scoreBefore": before, "scoreAfter": after})
            self.store.put_profile("demo-device-a", {"score": 104, "history": hist, "queuedAt": None})
            self.store.put_profile("demo-device-b", {"score": 100, "history": [], "queuedAt": None})
            self.step = 0
            self.command("A", "reset")
            self.command("B", "reset")

    def command(self, role, action, arg=None):
        with self.lock:
            self.seq += 1
            self.commands[role].append({"seq": self.seq, "action": action, "arg": arg})
            self.commands[role] = self.commands[role][-50:]

    def wait_for(self, cond, timeout=8.0):
        end = time.time() + timeout
        while time.time() < end:
            with self.lock:
                if cond():
                    return True
            time.sleep(0.2)
        return False

    def session(self):
        return self.store.get_session(KEY)

    def queued(self, dev):
        return any(e["deviceId"] == dev for e in self.store.list_queue(KEY))

    # ---- 시나리오 (앱 AppController.nextScenarioStep 과 같은 흐름)
    def ensure_a_confirmed(self):
        if self.session() is None:
            self.command("A", "ensureConfirmed")
            self.wait_for(lambda: self.session() is not None)

    def ensure_b_queued(self, eta_min=28):
        if not self.queued("demo-device-b"):
            self.command("B", "setEta", eta_min)
            self.command("B", "joinQueue", KEY)
            self.wait_for(lambda: self.queued("demo-device-b"))

    def jump_to(self, t):
        """시각 이동. 앞으로 갈 때는 1분씩 나눠 tick을 돌려, 사전 알림 같은 예약 알림이
        건너뛴 시각이 아니라 원래 울렸어야 할 시각으로 기록되게 한다."""
        with self.lock:
            cur = self.clock()
            if t > cur and t - cur <= 24 * 60 * MINUTE:
                step = cur + MINUTE
                while step < t:
                    self.clock.jump(step)
                    self.svc.tick()
                    step += MINUTE
            self.clock.jump(t)
            if t > cur:
                self.svc.tick()

    def next_step(self):
        idx = min(self.step + 1, len(STEPS) - 1)
        c = self.clock
        if idx == 1:
            self.jump_to(at(19, 0))
            self.command("A", "tab", "HOME")
            self.command("A", "plug")
        elif idx == 2:
            self.ensure_a_confirmed()
            self.jump_to(at(19, 10))
        elif idx == 3:
            self.ensure_a_confirmed()
            self.jump_to(at(22, 30))
            self.command("B", "tab", "BOARD")
            self.ensure_b_queued()
        elif idx == 4:
            self.ensure_a_confirmed(); self.ensure_b_queued()
            self.jump_to(at(22, 31))
            self.command("B", "tab", "HOME")
            self.command("B", "drive")
        elif idx == 5:
            self.ensure_a_confirmed(); self.ensure_b_queued()
            self.command("B", "drive")
            self.jump_to(at(22, 55))
        elif idx == 6:
            self.command("B", "arrive")
            self.command("A", "tab", "HOME")
            self.jump_to(at(23, 0))
        elif idx == 7:
            self.jump_to(at(23, 8))
            self.command("A", "unplug")
            self.command("A", "depart")
            self.command("B", "tab", "BOARD")
            self.wait_for(lambda: self.session() is None)
        elif idx == 8:
            self.jump_to(at(23, 12))
            self.command("B", "arrive")
            self.command("B", "tab", "BOARD")
            self.command("B", "arrivalCheck", KEY)
        elif idx == 9:
            self.command("A", "arrive")
            self.command("A", "tab", "MANNER")
        self.step = idx

    def branch(self, name):
        self.reset()
        time.sleep(1.5)  # 두 앱이 초기화 명령을 받을 시간
        c = self.clock
        if name == "slow":
            self.command("A", "power", 0.7)
        self.command("A", "tab", "HOME")
        self.command("A", "plug")
        time.sleep(1.0)
        self.ensure_a_confirmed()
        if name == "slow":
            self.jump_to(at(19, 20))
            return
        self.jump_to(at(22, 30))
        self.ensure_b_queued(28 if name == "grace" else 30)
        if name == "grace":
            planned = (self.session() or {}).get("planned", at(23, 10))
            self.jump_to(planned + (POLICY["graceMinutes"] + 1) * MINUTE)
        elif name == "noshow":
            self.jump_to(at(23, 8))
            self.command("A", "unplug")
            self.command("A", "depart")
            self.wait_for(lambda: self.session() is None)
            self.jump_to(max(at(23, 8), at(23, 0)) + (POLICY["queueHoldMinutes"] + 1) * MINUTE)
        self.command("B", "tab", "HOME")

    def start_action(self, name, arg=None):
        """감독 패널 버튼: 앞 동작이 끝나기 전에는 새 동작을 받지 않는다."""
        with self.lock:
            if self.busy:
                return False
            self.busy = True
        threading.Thread(target=self.action, args=(name, arg), daemon=True).start()
        return True

    def action(self, name, arg=None):
        try:
            if name == "reset":
                self.reset()
            elif name == "next":
                self.next_step()
            elif name in ("slow", "grace", "noshow"):
                self.branch(name)
            elif name == "speed":
                self.clock.set_speed(float(arg))
            elif name == "jump":
                self.jump_to(self.clock() + int(arg) * MINUTE)
            elif name in ("A:plug", "A:unplug", "A:depart", "A:arrive", "B:drive", "B:arrive", "B:park"):
                role, act = name.split(":")
                self.command(role, act)
            elif name == "B:commute":
                self.command("B", "setEta", 28)
                self.command("B", "drive")
            elif name == "A:slow":
                self.command("A", "power", 0.7)
        finally:
            with self.lock:
                self.busy = False


DEMO = Demo()


def sweeper():
    """EventBridge 1분 주기 Sweeper 역할: 사전 알림, 충전 완료, 유예 만료, 찜 자동 취소."""
    while True:
        try:
            with DEMO.lock:
                DEMO.svc.tick()
        except Exception as e:  # noqa: BLE001
            print("sweep error:", type(e).__name__, e)
        time.sleep(1.0)


# ---------------------------------------------------------------- HTTP

class Handler(BaseHTTPRequestHandler):
    server_version = "MannerVoltDemo/1"

    def log_message(self, fmt, *args):  # 요청마다 찍지 않는다
        pass

    def _send(self, code, payload, ctype="application/json; charset=utf-8"):
        body = payload if isinstance(payload, bytes) else json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return V.body(self.rfile.read(n).decode() if n else "")

    def _dev(self):
        dev = self.headers.get("X-Demo-Device", "")
        if dev not in DEVICES:
            raise ApiError(401, "시연 기기가 아닙니다.")
        return dev

    def _handle(self, method):
        u = urlparse(self.path)
        parts = [p for p in u.path.split("/") if p]
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        try:
            out = self._route(method, parts, q)
            if isinstance(out, tuple):
                self._send(200, out[0], out[1])
            else:
                self._send(200, out)
        except V.BadRequest as e:
            self._send(400, {"message": str(e)})
        except ApiError as e:
            self._send(e.code, {"message": e.message})
        except Exception as e:  # noqa: BLE001
            print("error:", type(e).__name__, e)
            self._send(500, {"message": "서버 오류가 발생했습니다."})

    def do_GET(self): self._handle("GET")
    def do_POST(self): self._handle("POST")
    def do_PUT(self): self._handle("PUT")
    def do_DELETE(self): self._handle("DELETE")

    def _route(self, method, p, q):
        # ---- 감독 패널
        if method == "GET" and p in ([], ["director"]):
            with open(os.path.join(os.path.dirname(__file__), "director.html"), "rb") as f:
                return f.read(), "text/html; charset=utf-8"
        if method == "GET" and p == ["demo", "director-state"]:
            return {"now": DEMO.clock(), "speed": DEMO.clock.speed, "step": DEMO.step, "busy": DEMO.busy,
                    "steps": [{"title": t, "detail": d} for t, d in STEPS]}
        if method == "POST" and p == ["demo", "action"]:
            b = self._body()
            return {"ok": DEMO.start_action(str(b.get("name", "")), b.get("arg"))}

        dev = self._dev()
        role = DEVICES[dev]
        # ---- 앱 동기화: 공유 시계 + 이 차량에 보낼 시연 명령
        if method == "GET" and p == ["demo", "sync"]:
            since = int(q.get("since", "0") or 0)
            with DEMO.lock:
                cmds = [c for c in DEMO.commands[role] if c["seq"] > since]
            return {"now": DEMO.clock(), "speed": DEMO.clock.speed, "latest": DEMO.seq, "commands": cmds}
        if method == "POST" and p == ["demo", "physical"]:
            b = self._body()
            key = V.charger_key(b.get("chargerKey"))
            with DEMO.lock:
                if b.get("plugged"):
                    if key.split("-")[1] not in OUT_OF_SERVICE:
                        DEMO.stations.physical.setdefault(key, V.ts(b.get("at")))
                else:
                    DEMO.stations.physical.pop(key, None)
            return {"ok": True}

        svc = DEMO.svc
        with DEMO.lock:
            if method == "GET" and len(p) == 4 and p[:2] == ["v1", "stations"] and p[3] == "board":
                return svc.board(dev, V.station_id(p[2]))
            if method == "GET" and p == ["v1", "stations", "nearby"]:
                return svc.nearby()
            if method == "GET" and len(p) == 4 and p[:2] == ["v1", "stations"] and p[3] == "match":
                return svc.match(dev, V.station_id(p[2]), V.ts(int(q.get("pluggedAt", "0"))))
            if method == "PUT" and p == ["v1", "sessions"]:
                b = self._body()
                V.only(b, {"chargerKey", "chargingStartedAt", "predictedCompletionAt", "plannedDepartureAt", "reason"})
                reason = b.get("reason", "USER")
                if reason not in ("USER", "RECALC"):
                    raise V.BadRequest("값이 올바르지 않습니다.")
                return svc.upsert_session(dev, V.charger_key(b.get("chargerKey")), V.ts(b.get("chargingStartedAt")),
                                          V.ts(b.get("predictedCompletionAt")), V.ts(b.get("plannedDepartureAt")), reason)
            if method == "POST" and p == ["v1", "sessions", "departure"]:
                b = self._body()
                return svc.report_departure(dev, V.charger_key(b.get("chargerKey")), V.flag(b.get("chargingEnded")),
                                            V.flag(b.get("unplugged")), V.flag(b.get("leftZone")), V.ts(b.get("observedAt")),
                                            V.flag(b.get("gearMoving", False)))
            if len(p) == 4 and p[:2] == ["v1", "chargers"] and p[3] == "queue":
                key = V.charger_key(p[2])
                if method == "POST":
                    eta = self._body().get("etaAt")
                    svc.join_queue(dev, key, None if eta is None else V.ts(eta))
                    return {"ok": True}
                if method == "DELETE":
                    svc.leave_queue(dev, key)
                    return {"ok": True}
            if method == "POST" and len(p) == 4 and p[:2] == ["v1", "chargers"] and p[3] == "arrival-check":
                b = self._body()
                if b.get("observed") not in ("EMPTY", "OCCUPIED"):
                    raise V.BadRequest("값이 올바르지 않습니다.")
                svc.arrival_check(dev, V.charger_key(p[2]), b["observed"])
                return {"ok": True}
            if method == "GET" and p == ["v1", "me"]:
                return svc.me(dev)
            if method == "GET" and p == ["v1", "events"]:
                return svc.events(dev, int(q.get("since", "0") or 0))
        raise ApiError(404, "찾을 수 없습니다.")


def main():
    threading.Thread(target=sweeper, daemon=True).start()
    srv = ThreadingHTTPServer((HOST, PORT), Handler)
    print(f"매너볼트 두 대 시연 서버: http://{HOST}:{PORT}/director  (에뮬레이터에서는 http://10.0.2.2:{PORT})")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
