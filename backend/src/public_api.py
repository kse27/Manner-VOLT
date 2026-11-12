"""한국환경공단 전기자동차 충전소 정보 API 연동 (FR-7).
- 키는 Secrets Manager에만 있고 응답이나 로그에 남기지 않는다 (SECURITY-12).
- 실패하거나 제한에 걸리면 캐시, 캐시도 없으면 샘플 데이터를 쓴다 (FR-7.3).
- 엔드포인트와 필드는 공공데이터포털 명세 기준. 실제 키로 호출해 필드를 확인할 것.
"""
import json
import os
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime

from core import KST, now_ms
from mvlog import log

BASE = "https://apis.data.go.kr/B552584/EvCharger"
CACHE_TTL_MS = 5 * 60_000

STAT = {"1": "UNKNOWN", "2": "AVAILABLE", "3": "IN_USE", "4": "OUT_OF_SERVICE", "5": "OUT_OF_SERVICE", "9": "UNKNOWN"}
TYPE = {"01": "DC차데모", "02": "완속 AC", "03": "DC차데모+AC3상", "04": "DC콤보", "05": "DC차데모+DC콤보",
        "06": "DC차데모+AC3상+DC콤보", "07": "AC3상", "08": "DC콤보(완속)", "09": "NACS", "10": "DC콤보+NACS"}

# 샘플 단지 (앱 SampleData.kt와 같은 충전기 배치)
SAMPLE_HOME = {
    "statId": "GV000001", "name": "그린빌 아파트 지하 2층 충전소", "underground": True, "rows": 2, "cols": 3,
    "chargers": [
        {"chgerId": "01", "label": "1번", "typeLabel": "완속 AC", "powerKw": 7.0, "stat": "2"},
        {"chgerId": "02", "label": "2번", "typeLabel": "완속 AC", "powerKw": 7.0, "stat": "2"},
        {"chgerId": "03", "label": "3번", "typeLabel": "완속 AC", "powerKw": 7.0, "stat": "2"},
        {"chgerId": "04", "label": "4번", "typeLabel": "완속 AC", "powerKw": 7.0, "stat": "2"},
        {"chgerId": "05", "label": "5번", "typeLabel": "완속 AC", "powerKw": 11.0, "stat": "2"},
        {"chgerId": "06", "label": "6번", "typeLabel": "급속 DC", "powerKw": 50.0, "stat": "4"},
    ],
}


def _parse_ts(s):
    try:
        return int(datetime.strptime(s, "%Y%m%d%H%M%S").replace(tzinfo=KST).timestamp() * 1000) if s else None
    except ValueError:
        return None


PAGE_ROWS = 100
MAX_PAGES = 5  # 충전소 하나에 충전기 500대까지
# 공공 API는 평소 0.1~1초에 답하지만 가끔 요청 하나가 15초 넘게 멈춘다.
# 짧게 끊고 다시 시도하며, 충전소들은 동시에 조회해 Lambda 제한 시간(10초) 안에 끝낸다.
REQUEST_TIMEOUT_SEC = 2.5
ATTEMPTS = 3


def _http_get_json(params: dict) -> dict:
    q = urllib.parse.urlencode(params)
    req = urllib.request.Request(f"{BASE}/getChargerInfo?{q}", headers={"Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=REQUEST_TIMEOUT_SEC) as r:  # nosec: 고정된 https 엔드포인트
        if r.status != 200:
            raise RuntimeError(f"status {r.status}")
        return json.loads(r.read(2_000_000))


class Stations:
    def __init__(self, store, api_key_loader, home_station_id: str, nearby_ids: list, http_get=None):
        self.s = store
        self._key_loader = api_key_loader
        self.home_id = home_station_id
        self.nearby_ids = nearby_ids
        self._http_get = http_get or _http_get_json
        self._mem = None

    # --- 공공 API
    def _get(self, params):
        for attempt in range(1, ATTEMPTS + 1):
            try:
                data = self._http_get(params)
                break
            except Exception:  # 시간 초과, 연결 오류
                if attempt == ATTEMPTS:
                    raise
        if str(data.get("resultCode", "00")) != "00":
            raise RuntimeError(f"result {data.get('resultCode')}")
        return data

    def _fetch_station(self, key, sid):
        out = []
        for page in range(1, MAX_PAGES + 1):
            data = self._get({"serviceKey": key, "pageNo": page, "numOfRows": PAGE_ROWS, "statId": sid, "dataType": "JSON"})
            items = (data.get("items") or {}).get("item") or []
            if isinstance(items, dict):
                items = [items]
            out += [i for i in items if i.get("statId") == sid and i.get("delYn") != "Y"]
            if not items or page * PAGE_ROWS >= int(data.get("totalCount") or 0):
                break
        return out

    def _fetch(self):
        """충전소 ID별로 동시에 조회하고, 결과가 많으면 다음 페이지도 받는다. 철거(delYn=Y) 충전기는 뺀다.
        단지 충전소 조회가 실패하면 예외를 올려 캐시/샘플을 쓰게 하고, 주변 충전소 실패는 그 충전소만 뺀다."""
        key = self._key_loader()
        ids = [i for i in dict.fromkeys([self.home_id, *self.nearby_ids]) if i]
        if not key or not ids:
            return None
        with ThreadPoolExecutor(max_workers=min(8, len(ids))) as pool:
            futures = {sid: pool.submit(self._fetch_station, key, sid) for sid in ids}
        out = []
        for sid, f in futures.items():
            try:
                out += f.result()
            except Exception as e:
                if sid == self.home_id:
                    raise
                log("WARN", "public api station failed", statId=sid, error=type(e).__name__)
        return out

    def _items(self):
        """(items, dataAsOf, source)"""
        now = now_ms()
        if self._mem and now - self._mem[1] < CACHE_TTL_MS:
            return self._mem
        cached, fetched = self.s.cache_get("chargers")
        if cached is not None and now - fetched < CACHE_TTL_MS:
            self._mem = (cached, fetched, "LIVE")
            return self._mem
        try:
            items = self._fetch()
            if items is not None:
                self.s.cache_put("chargers", items, now)
                self._mem = (items, now, "LIVE")
                return self._mem
        except Exception as e:  # 호출 실패, 제한 초과
            log("WARN", "public api failed", error=type(e).__name__)
        if cached is not None:
            return (cached, fetched, "CACHE")
        return (None, now, "SAMPLE")

    # --- 조회
    def station(self, station_id):
        items, as_of, source = self._items()
        if items:
            mine = [i for i in items if i.get("statId") == station_id]
            if mine:
                cols = 3
                chargers = []
                for n, i in enumerate(sorted(mine, key=lambda x: x.get("chgerId", ""))):
                    chargers.append({
                        "chargerKey": f"{i['statId']}-{i['chgerId']}", "label": f"{int(i['chgerId']) if i['chgerId'].isdigit() else i['chgerId']}번",
                        "typeLabel": TYPE.get(i.get("chgerType", ""), "기타"), "powerKw": float(i.get("output") or 7),
                        "slot": {"row": n // cols, "col": n % cols}, "publicStatus": STAT.get(str(i.get("stat")), "UNKNOWN"),
                        "nowChargingStartedAt": _parse_ts(i.get("nowTsdt")),
                    })
                underground = any(i.get("floorType") == "B" for i in mine)  # B: 지하, F: 지상
                return {"name": mine[0].get("statNm", ""), "underground": underground, "rows": (len(chargers) + cols - 1) // cols,
                        "cols": cols, "chargers": chargers, "dataAsOf": as_of, "source": source}
        if station_id != SAMPLE_HOME["statId"]:
            return None
        return {"name": SAMPLE_HOME["name"], "underground": True, "rows": 2, "cols": 3, "dataAsOf": as_of, "source": "SAMPLE",
                "chargers": [{"chargerKey": f"GV000001-{c['chgerId']}", "label": c["label"], "typeLabel": c["typeLabel"],
                              "powerKw": c["powerKw"], "slot": {"row": n // 3, "col": n % 3}, "publicStatus": STAT[c["stat"]],
                              "nowChargingStartedAt": None} for n, c in enumerate(SAMPLE_HOME["chargers"])]}

    def charger(self, key):
        stat_id = key.split("-")[0]
        st = self.station(stat_id)
        if st is None:
            return None
        return next((c for c in st["chargers"] if c["chargerKey"] == key), None)

    def nearby(self):
        items, as_of, source = self._items()
        if not items:
            return {"stations": [], "dataAsOf": as_of, "source": "SAMPLE"}
        by = {}
        home = None
        for i in items:
            if i.get("statId") == self.home_id:
                if home is None and _coord(i.get("lat")) is not None and _coord(i.get("lng")) is not None:
                    home = {"statId": i["statId"], "name": i.get("statNm", ""), "lat": _coord(i["lat"]), "lon": _coord(i["lng"])}
                continue
            st = by.setdefault(i["statId"], {"statId": i["statId"], "name": i.get("statNm", ""), "address": i.get("addr", ""),
                                             "operator": i.get("busiNm", ""), "useTime": i.get("useTime", ""), "distanceLabel": "",
                                             "lat": _coord(i.get("lat")), "lon": _coord(i.get("lng")), "chargers": []})
            st["chargers"].append({"chargerKey": f"{i['statId']}-{i['chgerId']}", "typeLabel": TYPE.get(i.get("chgerType", ""), "기타"),
                                   "outputKw": float(i.get("output") or 0), "status": STAT.get(str(i.get("stat")), "UNKNOWN"),
                                   "nowChargingStartedAt": _parse_ts(i.get("nowTsdt"))})
        out = {"stations": list(by.values()), "dataAsOf": as_of, "source": source}
        if home:
            out["home"] = home
        return out

    def alternatives(self, exclude_key):
        out = []
        home = self.station(exclude_key.split("-")[0])
        if home:
            out += [f"같은 충전소 {c['label']} ({c['typeLabel']})" for c in home["chargers"]
                    if c["chargerKey"] != exclude_key and c["publicStatus"] == "AVAILABLE" and self.s.get_session(c["chargerKey"]) is None]
        for st in self.nearby()["stations"]:
            out += [f"{st['name']} ({c['typeLabel']} {int(c['outputKw'])}kW)" for c in st["chargers"] if c["status"] == "AVAILABLE"]
        return out[:3]


def _coord(v):
    """공공 API 좌표 문자열 -> float. 비었거나 0이면 None (지도에 표시하지 않음)."""
    try:
        f = float(v)
    except (TypeError, ValueError):
        return None
    return f if f else None


def secret_loader(arn_env: str):
    cache = {}

    def load():
        if "v" not in cache:
            arn = os.environ.get(arn_env)
            if not arn:
                cache["v"] = ""
            else:
                import boto3
                v = boto3.client("secretsmanager").get_secret_value(SecretId=arn)["SecretString"].strip()
                cache["v"] = "" if v == "CHANGE_ME" else v
        return cache["v"]
    return load
