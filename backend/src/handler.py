"""API Gateway(REST, Lambda 프록시) 진입점과 Sweeper 진입점."""
import json
import os

import auth
import validate as V
from core import ApiError, Service
from mvlog import log
from public_api import Stations, secret_loader
from store import DynamoStore

_svc = None

HEADERS = {
    "Content-Type": "application/json; charset=utf-8",
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff",
    "Strict-Transport-Security": "max-age=31536000; includeSubDomains",
}


def service() -> Service:
    global _svc
    if _svc is None:
        store = DynamoStore(os.environ["TABLE_NAME"])
        stations = Stations(store, secret_loader("PUBLIC_API_SECRET_ARN"), os.environ.get("HOME_STATION_ID", "GV000001"),
                            [s for s in os.environ.get("NEARBY_STATION_IDS", "").split(",") if s])
        _svc = Service(store, stations)
    return _svc


def _resp(code, payload):
    return {"statusCode": code, "headers": HEADERS, "body": json.dumps(payload, ensure_ascii=False)}


def api(event, context):
    """전역 오류 처리기 (SECURITY-09, 15): 내부 정보 없이 일반 메시지만, 오류 시 거부."""
    rid = getattr(context, "aws_request_id", "-")
    route = f"{event.get('httpMethod')} {event.get('resource')}"
    try:
        out = _route(event, route)
        log("INFO", "request", route=route, status=200, requestId=rid)
        return _resp(200, out)
    except V.BadRequest as e:
        log("INFO", "bad request", route=route, status=400, requestId=rid)
        return _resp(400, {"message": str(e)})
    except ApiError as e:
        log("INFO", "rejected", route=route, status=e.code, requestId=rid)
        return _resp(e.code, {"message": e.message})
    except Exception as e:
        log("ERROR", "unhandled", route=route, error=type(e).__name__, requestId=rid)
        return _resp(500, {"message": "서버 오류가 발생했습니다."})


def _route(event, route):
    svc = service()
    key = auth.signing_key()
    headers = {k.lower(): v for k, v in (event.get("headers") or {}).items()}
    path = event.get("pathParameters") or {}
    query = event.get("queryStringParameters") or {}

    if route == "POST /v1/devices":
        dev = auth.new_device_id()
        svc.ensure_profile(dev)
        return auth.issue(dev, key)

    dev = auth.verify(headers.get("authorization"), key)
    if dev is None:
        raise ApiError(401, "인증이 필요합니다.")

    if route == "POST /v1/devices/refresh":
        return auth.issue(dev, key)
    if route == "GET /v1/stations/{stationId}/board":
        return svc.board(dev, V.station_id(path.get("stationId")))
    if route == "GET /v1/stations/nearby":
        return svc.nearby()
    if route == "GET /v1/stations/{stationId}/match":
        plugged = query.get("pluggedAt", "")
        if not plugged.isdigit() or len(plugged) > 13:
            raise V.BadRequest("값이 올바르지 않습니다.")
        return svc.match(dev, V.station_id(path.get("stationId")), V.ts(int(plugged)))
    if route == "PUT /v1/sessions":
        b = V.body(event.get("body"))
        V.only(b, {"chargerKey", "chargingStartedAt", "predictedCompletionAt", "plannedDepartureAt", "reason"})
        reason = b.get("reason", "USER")
        if reason not in ("USER", "RECALC"):
            raise V.BadRequest("값이 올바르지 않습니다.")
        return svc.upsert_session(dev, V.charger_key(b.get("chargerKey")), V.ts(b.get("chargingStartedAt")),
                                  V.ts(b.get("predictedCompletionAt")), V.ts(b.get("plannedDepartureAt")), reason)
    if route == "POST /v1/sessions/departure":
        b = V.body(event.get("body"))
        V.only(b, {"chargerKey", "chargingEnded", "unplugged", "leftZone", "observedAt", "gearMoving"})
        return svc.report_departure(dev, V.charger_key(b.get("chargerKey")), V.flag(b.get("chargingEnded")),
                                    V.flag(b.get("unplugged")), V.flag(b.get("leftZone")), V.ts(b.get("observedAt")),
                                    V.flag(b.get("gearMoving", False)))
    if route == "POST /v1/chargers/{chargerKey}/queue":
        b = V.body(event.get("body") or "{}")
        V.only(b, {"etaAt"})
        eta = b.get("etaAt")
        svc.join_queue(dev, V.charger_key(path.get("chargerKey")), None if eta is None else V.ts(eta))
        return {"ok": True}
    if route == "DELETE /v1/chargers/{chargerKey}/queue":
        svc.leave_queue(dev, V.charger_key(path.get("chargerKey")))
        return {"ok": True}
    if route == "POST /v1/chargers/{chargerKey}/arrival-check":
        b = V.body(event.get("body"))
        V.only(b, {"chargerKey", "observed"})
        k = V.charger_key(path.get("chargerKey"))
        if b.get("chargerKey") not in (None, k) or b.get("observed") not in ("EMPTY", "OCCUPIED"):
            raise V.BadRequest("값이 올바르지 않습니다.")
        svc.arrival_check(dev, k, b["observed"])
        return {"ok": True}
    if route == "GET /v1/me":
        return svc.me(dev)
    if route == "GET /v1/events":
        since = query.get("since", "0")
        if not since.isdigit() or len(since) > 13:
            raise V.BadRequest("값이 올바르지 않습니다.")
        return svc.events(dev, int(since))
    raise ApiError(404, "찾을 수 없습니다.")


def sweep(event, context):
    """EventBridge 1분 주기: 사전 알림, 충전 완료 안내, 유예 만료 안내."""
    try:
        service().tick()
    except Exception as e:
        log("ERROR", "sweep failed", error=type(e).__name__)
        raise
