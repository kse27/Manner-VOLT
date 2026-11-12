"""기기 토큰 (SECURITY-08). 서버가 기기 ID를 만들고 HMAC-SHA256으로 서명한다."""
import base64
import hashlib
import hmac
import json
import os
import time
import uuid

TOKEN_TTL_SEC = 30 * 24 * 3600
_secret_cache: dict = {}


def _b64(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).rstrip(b"=").decode()


def _unb64(s: str) -> bytes:
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def signing_key() -> bytes:
    """Secrets Manager에서 서명 키를 읽어 메모리에 캐시한다 (SECURITY-12)."""
    if "k" not in _secret_cache:
        arn = os.environ.get("TOKEN_SECRET_ARN")
        if not arn:
            raise RuntimeError("token secret not configured")
        import boto3
        value = boto3.client("secretsmanager").get_secret_value(SecretId=arn)["SecretString"]
        if len(value) < 32:
            raise RuntimeError("token secret too short")
        _secret_cache["k"] = value.encode()
    return _secret_cache["k"]


def issue(device_id: str, key: bytes, now: float | None = None) -> dict:
    now = int(now if now is not None else time.time())
    payload = _b64(json.dumps({"sub": device_id, "iat": now, "exp": now + TOKEN_TTL_SEC}, separators=(",", ":")).encode())
    sig = _b64(hmac.new(key, payload.encode(), hashlib.sha256).digest())
    return {"deviceId": device_id, "token": f"{payload}.{sig}", "expiresAt": (now + TOKEN_TTL_SEC) * 1000}


def new_device_id() -> str:
    return str(uuid.uuid4())


def verify(header: str | None, key: bytes, now: float | None = None) -> str | None:
    """유효하면 기기 ID, 아니면 None (fail closed)."""
    try:
        if not header or not header.startswith("Bearer "):
            return None
        token = header[7:].strip()
        if len(token) > 512 or token.count(".") != 1:
            return None
        payload, sig = token.split(".")
        expected = _b64(hmac.new(key, payload.encode(), hashlib.sha256).digest())
        if not hmac.compare_digest(expected, sig):
            return None
        claims = json.loads(_unb64(payload))
        if int(claims["exp"]) <= int(now if now is not None else time.time()):
            return None
        sub = str(claims["sub"])
        uuid.UUID(sub)
        return sub
    except Exception:
        return None
