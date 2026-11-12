"""DynamoDB 저장소 (단일 테이블, pk/sk). 테이블은 SSE-KMS와 PITR로 만든다 (template.yaml)."""
import json
import time
import uuid
from datetime import datetime, timezone

EVENT_TTL_SEC = 7 * 24 * 3600


def _j(item):
    return json.loads(item["data"]) if item and "data" in item else None


class DynamoStore:
    def __init__(self, table_name: str):
        import boto3
        self.t = boto3.resource("dynamodb").Table(table_name)

    def _get(self, pk, sk):
        return _j(self.t.get_item(Key={"pk": pk, "sk": sk}, ConsistentRead=True).get("Item"))

    def _put(self, pk, sk, data, **extra):
        self.t.put_item(Item={"pk": pk, "sk": sk, "data": json.dumps(data, ensure_ascii=False), **extra})

    def _query(self, pk, prefix, limit=100):
        from boto3.dynamodb.conditions import Key
        r = self.t.query(KeyConditionExpression=Key("pk").eq(pk) & Key("sk").begins_with(prefix), Limit=limit, ConsistentRead=True)
        return r.get("Items", [])

    # profile
    def get_profile(self, dev): return self._get(f"DEVICE#{dev}", "PROFILE")
    def put_profile(self, dev, p): self._put(f"DEVICE#{dev}", "PROFILE", p)

    # session
    def get_session(self, key): return self._get(f"CHG#{key}", "SESSION")
    def put_session(self, key, s): self._put(f"CHG#{key}", "SESSION", s)

    def create_session(self, key, s) -> bool:
        try:
            self.t.put_item(Item={"pk": f"CHG#{key}", "sk": "SESSION", "data": json.dumps(s, ensure_ascii=False)},
                            ConditionExpression="attribute_not_exists(pk)")
        except self.t.meta.client.exceptions.ConditionalCheckFailedException:
            return False
        self.t.put_item(Item={"pk": "ACTIVE", "sk": f"CHG#{key}", "data": "{}"})
        return True

    def delete_session(self, key):
        self.t.delete_item(Key={"pk": f"CHG#{key}", "sk": "SESSION"})
        self.t.delete_item(Key={"pk": "ACTIVE", "sk": f"CHG#{key}"})

    def active_keys(self):
        return [i["sk"][4:] for i in self._query("ACTIVE", "CHG#", 500)]

    # state
    def get_state(self, key): return self._get(f"CHG#{key}", "STATE")
    def put_state(self, key, st):
        self._put(f"CHG#{key}", "STATE", st)
        # 빈 뒤 대기자 도착을 기다리는 충전기 목록 (찜 자동 취소용, FR-12.4)
        if st.get("awaiting"):
            self.t.put_item(Item={"pk": "AWAITING", "sk": f"CHG#{key}", "data": "{}"})
        else:
            self.t.delete_item(Key={"pk": "AWAITING", "sk": f"CHG#{key}"})

    def awaiting_keys(self):
        return [i["sk"][4:] for i in self._query("AWAITING", "CHG#", 500)]

    # queue
    def list_queue(self, key): return [_j(i) for i in self._query(f"CHG#{key}", "QUEUE#")]
    def put_queue(self, key, dev, e): self._put(f"CHG#{key}", f"QUEUE#{dev}", e)
    def delete_queue(self, key, dev): self.t.delete_item(Key={"pk": f"CHG#{key}", "sk": f"QUEUE#{dev}"})

    # events
    def add_event(self, dev, e):
        self._put(f"DEVICE#{dev}", f"EVENT#{e['at']:013d}#{e['id']}", e, ttl=int(time.time()) + EVENT_TTL_SEC)

    def events_since(self, dev, since):
        from boto3.dynamodb.conditions import Key
        r = self.t.query(KeyConditionExpression=Key("pk").eq(f"DEVICE#{dev}") & Key("sk").between(f"EVENT#{since + 1:013d}", "EVENT#~"),
                         Limit=50, ConsistentRead=True)
        return [_j(i) for i in r.get("Items", [])]

    # audit (SECURITY-13): TTL 없음
    def add_audit(self, a):
        day = datetime.fromtimestamp(a["at"] / 1000, timezone.utc).strftime("%Y%m%d")
        self._put(f"AUDIT#{day}", f"{a['at']:013d}#{uuid.uuid4()}", a)

    # rate limit (SECURITY-11): 기기당 분당 쓰기 횟수
    def rate_hit(self, dev, limit) -> bool:
        minute = int(time.time() // 60)
        try:
            self.t.update_item(
                Key={"pk": f"RATE#{dev}", "sk": str(minute)},
                UpdateExpression="ADD n :one SET #ttl = :ttl",
                ConditionExpression="attribute_not_exists(n) OR n < :lim",
                ExpressionAttributeNames={"#ttl": "ttl"},
                ExpressionAttributeValues={":one": 1, ":lim": limit, ":ttl": minute * 60 + 300},
            )
            return True
        except self.t.meta.client.exceptions.ConditionalCheckFailedException:
            return False

    # cache
    def cache_get(self, name):
        item = self.t.get_item(Key={"pk": "CACHE", "sk": name}).get("Item")
        return (_j(item), int(item["fetchedAt"])) if item else (None, 0)

    def cache_put(self, name, value, fetched_at):
        self._put("CACHE", name, value, fetchedAt=fetched_at)


class MemoryStore:
    """테스트용 메모리 저장소 (DynamoStore와 같은 동작)."""

    def __init__(self):
        self.d = {}
        self.audit = []
        self.events = {}
        self.cache = {}

    def get_profile(self, dev): return json.loads(json.dumps(self.d.get(("P", dev)))) if ("P", dev) in self.d else None
    def put_profile(self, dev, p): self.d[("P", dev)] = json.loads(json.dumps(p))
    def get_session(self, key): return json.loads(json.dumps(self.d[("S", key)])) if ("S", key) in self.d else None
    def put_session(self, key, s): self.d[("S", key)] = json.loads(json.dumps(s))

    def create_session(self, key, s):
        if ("S", key) in self.d:
            return False
        self.put_session(key, s)
        return True

    def delete_session(self, key): self.d.pop(("S", key), None)
    def active_keys(self): return [k[1] for k in self.d if k[0] == "S"]
    def get_state(self, key): return self.d.get(("T", key))
    def put_state(self, key, st): self.d[("T", key)] = dict(st)
    def awaiting_keys(self): return [k[1] for k, v in self.d.items() if k[0] == "T" and v.get("awaiting")]
    def list_queue(self, key): return [dict(v) for k, v in self.d.items() if k[0] == "Q" and k[1] == key]
    def put_queue(self, key, dev, e): self.d[("Q", key, dev)] = dict(e)
    def delete_queue(self, key, dev): self.d.pop(("Q", key, dev), None)
    def add_event(self, dev, e): self.events.setdefault(dev, []).append(e)
    def events_since(self, dev, since): return [e for e in self.events.get(dev, []) if e["at"] > since]
    def add_audit(self, a): self.audit.append(a)
    def rate_hit(self, dev, limit): return True
    def cache_get(self, name): return self.cache.get(name, (None, 0))
    def cache_put(self, name, value, fetched_at): self.cache[name] = (value, fetched_at)
