"""구조화 JSON 로그 (SECURITY-03). 토큰, 좌표, 키, 요청 본문은 기록하지 않는다."""
import json
import sys
import time

_BLOCKED = {"token", "authorization", "serviceKey", "lat", "lng", "lon", "body", "secret"}


def log(level: str, msg: str, **fields):
    safe = {k: v for k, v in fields.items() if k not in _BLOCKED}
    sys.stdout.write(json.dumps({"ts": int(time.time() * 1000), "level": level, "msg": msg, **safe}, ensure_ascii=False) + "\n")
