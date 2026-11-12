"""입력 검증 (SECURITY-05): 허용 목록 정규식, 길이, 범위."""
import json
import re

CHARGER_KEY = re.compile(r"^[A-Z0-9]{2,12}-[0-9]{1,4}$")
STATION_ID = re.compile(r"^[A-Z0-9]{2,12}$")
MAX_BODY = 2048
MIN_TS = 1_577_836_800_000   # 2020-01-01
MAX_TS = 4_102_444_800_000   # 2100-01-01


class BadRequest(Exception):
    pass


def body(raw: str | None) -> dict:
    if raw is None or raw == "":
        return {}
    if len(raw) > MAX_BODY:
        raise BadRequest("요청이 너무 깁니다.")
    try:
        data = json.loads(raw)
    except ValueError:
        raise BadRequest("요청 형식이 올바르지 않습니다.")
    if not isinstance(data, dict):
        raise BadRequest("요청 형식이 올바르지 않습니다.")
    return data


def charger_key(v) -> str:
    if not isinstance(v, str) or not CHARGER_KEY.match(v):
        raise BadRequest("충전기 ID가 올바르지 않습니다.")
    return v


def station_id(v) -> str:
    if not isinstance(v, str) or not STATION_ID.match(v):
        raise BadRequest("충전소 ID가 올바르지 않습니다.")
    return v


def ts(v) -> int:
    if isinstance(v, bool) or not isinstance(v, int) or not (MIN_TS <= v <= MAX_TS):
        raise BadRequest("시각 값이 올바르지 않습니다.")
    return v


def flag(v) -> bool:
    if not isinstance(v, bool):
        raise BadRequest("값이 올바르지 않습니다.")
    return v


def only(data: dict, allowed: set):
    extra = set(data) - allowed
    if extra:
        raise BadRequest("알 수 없는 필드가 있습니다.")
