"""로컬 확인용: .env.local의 키로 한국환경공단 충전소 API(getChargerInfo)를 한 번 호출한다.

- 키는 출력하지 않는다. 오류 메시지에서도 가린다.
- 응답 필드가 public_api.py 파싱 코드와 맞는지 확인하고, 실제 Stations 변환 결과를 보여 준다.

사용: python3 backend/tools/probe_public_api.py [statId ...]
  statId를 주면 첫 번째를 단지, 나머지를 주변 충전소로 보고 서버와 같은 경로(Stations)로 조회한다.
  비우면 강남구(zscode 11680) 50건을 받아 필드를 확인하고, 그중 충전소 4곳으로 같은 확인을 한다.
"""
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "backend", "src"))

from public_api import BASE, STAT, TYPE, Stations  # noqa: E402
from store import MemoryStore  # noqa: E402

USED_FIELDS = ["statId", "statNm", "chgerId", "chgerType", "addr", "useTime", "busiNm", "stat", "output", "nowTsdt"]


def load_env(path):
    env = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith("#") and "=" in line:
                k, v = line.split("=", 1)
                env[k.strip()] = v.strip().strip('"').strip("'")
    return env


def main():
    given = sys.argv[1:]
    zscode, rows = "11680", 50
    env = load_env(os.path.join(ROOT, ".env.local"))
    key = env.get("KECO_EV_API_KEY", "")
    if not key:
        sys.exit(".env.local에 KECO_EV_API_KEY가 없습니다.")
    key = urllib.parse.unquote(key)  # 인코딩 키를 넣었어도 이중 인코딩되지 않게
    base = (env.get("KECO_EV_API_BASE_URL") or BASE).rstrip("/")
    if base.endswith("/getChargerInfo"):
        base = base[: -len("/getChargerInfo")]

    def hide(s):
        return str(s).replace(key, "***").replace(urllib.parse.quote(key, safe=""), "***")

    q = urllib.parse.urlencode({"serviceKey": key, "pageNo": 1, "numOfRows": rows, "zscode": zscode, "dataType": "JSON"})
    print(f"요청: {base}/getChargerInfo  zscode={zscode} numOfRows={rows}")
    try:
        with urllib.request.urlopen(f"{base}/getChargerInfo?{q}", timeout=15) as r:
            raw = r.read(5_000_000).decode("utf-8", "replace")
            print("HTTP", r.status)
    except urllib.error.HTTPError as e:
        sys.exit(f"HTTP 오류 {e.code}: {hide(e.read(500).decode('utf-8', 'replace'))}")
    except Exception as e:
        sys.exit(f"호출 실패: {type(e).__name__}: {hide(e)}")

    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        sys.exit("JSON이 아닌 응답 (키 미승인/오류일 수 있음):\n" + hide(raw[:600]))

    print("최상위 키:", list(data.keys()))
    print("결과:", {k: data.get(k) for k in ("resultCode", "resultMsg", "totalCount", "numOfRows", "pageNo")})
    items = (data.get("items") or {}).get("item") or []
    if isinstance(items, dict):
        items = [items]
    print("받은 충전기 수:", len(items))
    if not items and not given:
        return
    if given:
        return check_stations(key, hide, given[0], given[1:])

    fields = sorted({k for i in items for k in i})
    print("\n응답 필드:", fields)
    print("코드에서 쓰는 필드 중 없는 것:", [f for f in USED_FIELDS if f not in fields] or "없음")
    print("stat 값 분포:", {v: sum(1 for i in items if str(i.get("stat")) == v) for v in sorted({str(i.get("stat")) for i in items})})
    print("매핑 안 되는 stat:", sorted({str(i.get("stat")) for i in items} - STAT.keys()) or "없음")
    print("매핑 안 되는 chgerType:", sorted({str(i.get("chgerType")) for i in items} - TYPE.keys()) or "없음")
    print("\n첫 항목:")
    print(json.dumps(items[0], ensure_ascii=False, indent=2))

    stat_ids = list(dict.fromkeys(i["statId"] for i in items))
    check_stations(key, hide, stat_ids[0], stat_ids[1:4])


def check_stations(key, hide, home_id, nearby_ids):
    """서버와 같은 경로: 충전소별 조회 + 페이지 넘김 + 철거 제외 + 지하 판단."""
    calls = []

    def counted(params):
        calls.append(params["statId"])
        from public_api import _http_get_json
        return _http_get_json(params)

    st = Stations(MemoryStore(), lambda: key, home_id, nearby_ids, http_get=counted)
    try:
        home = st.station(home_id)
    except Exception as e:
        sys.exit(f"변환 실패: {type(e).__name__}: {hide(e)}")
    print(f"\n공공 API 호출 {len(calls)}회 (충전소 {len(set(calls))}곳)")
    if home is None:
        print(f"[배치도 변환] {home_id}: 공공 API에 없음")
    else:
        print(f"[배치도 변환] {home['name']} · source={home['source']} · 지하={home['underground']} · {home['rows']}x{home['cols']}")
        for c in home["chargers"]:
            print(f"  {c['label']:>4} {c['typeLabel']:<14} {c['powerKw']:>5}kW  {c['publicStatus']:<15} 충전시작={c['nowChargingStartedAt']}")
    nb = st.nearby()
    print(f"\n[주변 충전소 변환] {len(nb['stations'])}곳")
    for s in nb["stations"]:
        print(f"  {s['name']} · {s['address']} · {s['operator']} · 충전기 {len(s['chargers'])}대")


if __name__ == "__main__":
    main()
