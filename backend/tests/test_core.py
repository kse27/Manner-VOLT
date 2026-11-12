"""백엔드 규칙 테스트: 기획서 4항 시나리오, 권한, 토큰, 입력 검증."""
import os
import sys
import unittest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

import auth  # noqa: E402
import validate as V  # noqa: E402
from core import KST, MINUTE, ApiError, Service, max_departure, score_delta, verdict  # noqa: E402
from public_api import Stations  # noqa: E402
from store import MemoryStore  # noqa: E402

DAY = 1_800_000_000_000 - (1_800_000_000_000 % (24 * 60 * MINUTE))
KEY = "GV000001-03"


def at(h, m):
    return DAY + (h * 60 + m) * MINUTE


class Clock:
    def __init__(self, t):
        self.t = t

    def __call__(self):
        return self.t


def make():
    store = MemoryStore()
    clock = Clock(at(19, 0))
    svc = Service(store, Stations(store, lambda: "", "GV000001", []), clock=clock)
    return svc, store, clock


class ScenarioTest(unittest.TestCase):
    def test_full_scenario(self):
        svc, store, clock = make()
        svc.upsert_session("A", KEY, at(19, 0), at(23, 0), at(23, 10))
        clock.t = at(22, 30)
        tile = next(c for c in svc.board("B", "GV000001")["chargers"] if c["chargerKey"] == KEY)
        self.assertEqual(tile["status"], "IN_USE")
        self.assertEqual(tile["plannedDepartureAt"], at(23, 10))
        svc.join_queue("B", KEY)
        clock.t = at(22, 55); svc.tick()
        self.assertIn("PRE_DEPARTURE", [e["type"] for e in svc.events("B", 0)])
        clock.t = at(23, 0); svc.tick()
        self.assertIn("CHARGE_COMPLETE", [e["type"] for e in svc.events("A", 0)])
        clock.t = at(23, 8)
        self.assertEqual(svc.report_departure("A", KEY, True, True, False, clock.t)["verdict"], "PARKED")
        self.assertEqual(svc.report_departure("A", KEY, True, True, False, clock.t, True)["verdict"], "SUSPECT")
        r = svc.report_departure("A", KEY, True, True, True, clock.t, True)
        self.assertEqual((r["verdict"], r["scoreAfter"]), ("CONFIRMED", 102))
        self.assertIn("CHARGER_AVAILABLE", [e["type"] for e in svc.events("B", 0)])
        svc.arrival_check("B", KEY, "EMPTY")
        self.assertIn("ARRIVAL_CONFIRMED", [e["type"] for e in svc.events("B", 0)])
        self.assertTrue(any(a["action"] == "SCORE" and a["before"] == "100" and a["after"] == "102" for a in store.audit))

    def test_only_owner_reports(self):
        svc, _, clock = make()
        svc.upsert_session("A", KEY, at(19, 0), at(23, 0), at(23, 10))
        with self.assertRaises(ApiError) as e:
            svc.report_departure("B", KEY, True, True, True, clock.t, True)
        self.assertEqual(e.exception.code, 403)

    def test_other_device_cannot_take_charger(self):
        svc, _, _ = make()
        svc.upsert_session("A", KEY, at(19, 0), at(23, 0), at(23, 10))
        with self.assertRaises(ApiError):
            svc.upsert_session("B", KEY, at(19, 0), at(23, 0), at(23, 10))

    def test_grace_expired_alternatives(self):
        svc, _, clock = make()
        svc.upsert_session("A", KEY, at(19, 0), at(23, 0), at(23, 10))
        svc.join_queue("B", KEY)
        clock.t = at(23, 26); svc.tick()
        e = next(e for e in svc.events("B", 0) if e["type"] == "GRACE_EXPIRED")
        self.assertTrue(e["alternatives"])

    def test_queue_limits(self):
        svc, _, _ = make()
        svc.upsert_session("A", KEY, at(19, 0), at(23, 0), at(23, 10))
        for d in ("B", "C", "D"):
            svc.join_queue(d, KEY)
        with self.assertRaises(ApiError):
            svc.join_queue("E", KEY)

    def test_stale_timestamp_rejected(self):
        svc, _, clock = make()
        svc.upsert_session("A", KEY, at(19, 0), at(23, 0), at(23, 10))
        with self.assertRaises(ApiError):
            svc.report_departure("A", KEY, True, True, True, clock.t - 60 * MINUTE, True)

    def test_score_rules_match_app(self):
        self.assertEqual([score_delta(x) for x in (-2, 5, 6, 25, 600)], [2, 2, -3, -6, -20])


class FairnessRulesTest(unittest.TestCase):
    """FR-8, 12, 13, 14: 앱 LocalServer와 같은 규칙인지 확인한다."""

    def test_departure_needs_unplug_gear_and_zone(self):
        for unplugged in (False, True):
            for left in (False, True):
                for moving in (False, True):
                    self.assertEqual(verdict(unplugged, left, moving) == "CONFIRMED", unplugged and left and moving)

    def test_cap_is_buffer_or_law(self):
        start = at(19, 0)
        self.assertEqual(max_departure(start, at(23, 0), 7.0), at(23, 30))
        self.assertEqual(max_departure(start, at(19, 50), 100.0), at(20, 0))  # 급속 1시간
        svc, _, _ = make()
        with self.assertRaises(ApiError) as e:
            svc.upsert_session("A", KEY, at(19, 0), at(23, 0), at(23, 31))
        self.assertEqual(e.exception.code, 400)

    def test_extension_limits_notify_and_no_bonus(self):
        svc, store, clock = make()
        svc.upsert_session("A", KEY, at(19, 0), at(23, 0), at(23, 0))
        svc.join_queue("B", KEY)
        clock.t = at(22, 0)
        svc.upsert_session("A", KEY, at(19, 0), at(23, 0), at(23, 10))
        self.assertTrue(any("연장" in e["title"] for e in svc.events("B", 0)))
        svc.upsert_session("A", KEY, at(19, 0), at(23, 0), at(23, 20))
        with self.assertRaises(ApiError) as e:
            svc.upsert_session("A", KEY, at(19, 0), at(23, 0), at(23, 30))
        self.assertEqual(e.exception.code, 409)
        clock.t = at(23, 18)
        r = svc.report_departure("A", KEY, True, True, True, clock.t, True)
        self.assertEqual(r["scoreBefore"], r["scoreAfter"])

    def test_recalc_not_counted_as_extension(self):
        svc, _, clock = make()
        clock.t = at(19, 20)
        svc.upsert_session("A", KEY, at(19, 0), at(23, 0), at(23, 10))
        svc.upsert_session("A", KEY, at(19, 0), at(24, 43), at(24, 55), "RECALC")
        tile = next(c for c in svc.board("A", "GV000001")["chargers"] if c["chargerKey"] == KEY)
        self.assertEqual(tile["extensionsUsed"], 0)
        self.assertEqual(tile["plannedDepartureAt"], at(24, 55))

    def test_queue_hold_expires_and_promotes_next(self):
        svc, store, clock = make()
        svc.upsert_session("A", KEY, at(19, 0), at(23, 0), at(23, 10))
        svc.join_queue("B", KEY, at(23, 0))
        clock.t = at(22, 40)
        svc.join_queue("C", KEY, at(23, 30))
        clock.t = at(23, 8)
        svc.report_departure("A", KEY, True, True, True, clock.t, True)
        clock.t = at(23, 18); svc.tick()
        self.assertFalse(any(e["type"] == "QUEUE_CANCELLED" for e in svc.events("B", 0)))
        clock.t = at(23, 19); svc.tick()
        self.assertTrue(any(e["type"] == "QUEUE_CANCELLED" for e in svc.events("B", 0)))
        self.assertTrue(any(e["type"] == "CHARGER_AVAILABLE" and "1순위" in e["body"] for e in svc.events("C", 0)))
        self.assertIsNone(store.get_profile("B").get("queuedAt"))

    def test_match_and_unshared_estimate_from_public_data(self):
        def ts(ms):
            from datetime import datetime
            return datetime.fromtimestamp(ms / 1000, KST).strftime("%Y%m%d%H%M%S")
        rows = [
            {"statId": "HOME01", "statNm": "단지", "chgerId": "01", "chgerType": "02", "output": "7", "stat": "3", "nowTsdt": ts(at(19, 0)), "floorType": "B", "delYn": "N"},
            {"statId": "HOME01", "statNm": "단지", "chgerId": "02", "chgerType": "02", "output": "7", "stat": "3", "nowTsdt": ts(at(18, 0)), "floorType": "B", "delYn": "N"},
            {"statId": "HOME01", "statNm": "단지", "chgerId": "03", "chgerType": "02", "output": "7", "stat": "2", "nowTsdt": "", "floorType": "B", "delYn": "N"},
        ]
        store = MemoryStore()
        clock = Clock(at(19, 2))
        get = lambda params: {"resultCode": "00", "totalCount": len(rows), "items": {"item": rows}}
        svc = Service(store, Stations(store, lambda: "k", "HOME01", [], http_get=get), clock=clock)
        m = svc.match("A", "HOME01", at(19, 1))
        self.assertEqual([c["chargerKey"] for c in m["candidates"]], ["HOME01-01"])
        tiles = {c["chargerKey"]: c for c in svc.board("B", "HOME01")["chargers"]}
        self.assertIsNone(tiles["HOME01-02"]["ownerScore"])
        self.assertGreater(tiles["HOME01-02"]["estimatedCompletionAt"], at(18, 0))
        svc.upsert_session("A", "HOME01-01", at(19, 0), at(23, 0), at(23, 10))
        self.assertEqual(svc.board("B", "HOME01")["chargers"][0]["ownerScore"], 100)
        self.assertEqual(svc.match("C", "HOME01", at(19, 1))["candidates"], [])  # 약속이 생기면 후보에서 빠짐


class AuthTest(unittest.TestCase):
    key = b"k" * 40

    def test_roundtrip(self):
        reg = auth.issue(auth.new_device_id(), self.key, now=1000)
        self.assertEqual(auth.verify("Bearer " + reg["token"], self.key, now=1001), reg["deviceId"])

    def test_rejects_tampered_and_expired(self):
        reg = auth.issue(auth.new_device_id(), self.key, now=1000)
        p, s = reg["token"].split(".")
        self.assertIsNone(auth.verify(f"Bearer {p}.{s[:-1]}A", self.key, now=1001))
        self.assertIsNone(auth.verify("Bearer " + reg["token"], b"x" * 40, now=1001))
        self.assertIsNone(auth.verify("Bearer " + reg["token"], self.key, now=1000 + auth.TOKEN_TTL_SEC + 1))
        self.assertIsNone(auth.verify(None, self.key))


class ValidateTest(unittest.TestCase):
    def test_inputs(self):
        self.assertEqual(V.charger_key("GV000001-03"), "GV000001-03")
        for bad in ("gv-1", "GV000001-03; DROP", "", None, "A" * 40 + "-1"):
            with self.assertRaises(V.BadRequest):
                V.charger_key(bad)
        with self.assertRaises(V.BadRequest):
            V.ts(True)
        with self.assertRaises(V.BadRequest):
            V.body("x" * 5000)
        with self.assertRaises(V.BadRequest):
            V.only({"a": 1, "evil": 2}, {"a"})


class PublicApiTest(unittest.TestCase):
    """실제 응답 형식(getChargerInfo)을 흉내 낸 가짜 HTTP로 조회 규칙을 확인한다."""

    def fake(self, stations, fail=False):
        calls = []

        def get(params):
            calls.append((params["statId"], params["pageNo"]))
            if fail:
                return {"resultCode": "30", "resultMsg": "SERVICE KEY IS NOT REGISTERED ERROR."}
            rows = stations.get(params["statId"], [])
            n, p = params["numOfRows"], params["pageNo"]
            return {"resultCode": "00", "totalCount": len(rows), "items": {"item": rows[(p - 1) * n:p * n]}}
        return get, calls

    @staticmethod
    def row(sid, cid, floor="F", deleted="N", stat="2"):
        return {"statId": sid, "statNm": f"{sid} 충전소", "chgerId": cid, "chgerType": "02", "output": "7", "stat": stat,
                "nowTsdt": "", "floorType": floor, "delYn": deleted, "addr": "주소", "busiNm": "운영사", "useTime": "24시간"}

    def test_pages_until_total_and_queries_each_station(self):
        home = [self.row("HOME01", f"{n:03d}") for n in range(1, 151)]  # 150대 → 2페이지
        get, calls = self.fake({"HOME01": home, "NEAR01": [self.row("NEAR01", "01")]})
        st = Stations(MemoryStore(), lambda: "k", "HOME01", ["NEAR01"], http_get=get)
        self.assertEqual(len(st.station("HOME01")["chargers"]), 150)
        self.assertEqual(sorted(calls), [("HOME01", 1), ("HOME01", 2), ("NEAR01", 1)])
        self.assertEqual([s["statId"] for s in st.nearby()["stations"]], ["NEAR01"])

    def test_skips_deleted_chargers(self):
        get, _ = self.fake({"HOME01": [self.row("HOME01", "01"), self.row("HOME01", "02", deleted="Y")]})
        st = Stations(MemoryStore(), lambda: "k", "HOME01", [], http_get=get)
        self.assertEqual([c["chargerKey"] for c in st.station("HOME01")["chargers"]], ["HOME01-01"])

    def test_underground_from_floor_type(self):
        get, _ = self.fake({"UNDER1": [self.row("UNDER1", "01", floor="B")], "GROUND": [self.row("GROUND", "01")]})
        st = Stations(MemoryStore(), lambda: "k", "UNDER1", ["GROUND"], http_get=get)
        self.assertTrue(st.station("UNDER1")["underground"])
        self.assertFalse(st.station("GROUND")["underground"])

    def test_api_error_falls_back_to_sample(self):
        get, _ = self.fake({}, fail=True)
        st = Stations(MemoryStore(), lambda: "k", "GV000001", [], http_get=get)
        self.assertEqual(st.station("GV000001")["source"], "SAMPLE")

    def test_retries_timeouts_and_drops_only_failed_nearby(self):
        tries = {"HOME01": 0}
        rows = {"HOME01": [self.row("HOME01", "01")], "NEAR01": [self.row("NEAR01", "01")]}

        def get(params):
            sid = params["statId"]
            if sid == "HOME01":
                tries[sid] += 1
                if tries[sid] < 3:  # 두 번 멈추고 세 번째에 성공
                    raise TimeoutError()
            if sid == "DOWN01":
                raise TimeoutError()
            return {"resultCode": "00", "totalCount": len(rows[sid]), "items": {"item": rows[sid]}}
        st = Stations(MemoryStore(), lambda: "k", "HOME01", ["NEAR01", "DOWN01"], http_get=get)
        self.assertEqual(st.station("HOME01")["source"], "LIVE")
        self.assertEqual(tries["HOME01"], 3)
        self.assertEqual([s["statId"] for s in st.nearby()["stations"]], ["NEAR01"])

    def test_home_failure_uses_sample(self):
        def get(params):
            raise TimeoutError()
        st = Stations(MemoryStore(), lambda: "k", "GV000001", [], http_get=get)
        self.assertEqual(st.station("GV000001")["source"], "SAMPLE")

    def test_no_key_makes_no_calls(self):
        get, calls = self.fake({})
        Stations(MemoryStore(), lambda: "", "HOME01", [], http_get=get).station("GV000001")
        self.assertEqual(calls, [])


if __name__ == "__main__":
    unittest.main()
