import os
import uuid
from datetime import datetime, timezone
from typing import Optional

import psycopg
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

DATABASE_URL = os.getenv(
    "DATABASE_URL",
    "postgresql://familycontrol:familycontrol@localhost:5432/familycontrol",
)

app = FastAPI(title="FamilyControl API", version="1.0.0")


def db():
    return psycopg.connect(DATABASE_URL)


def init_db():
    with db() as conn:
        with conn.cursor() as cur:
            cur.execute("""
                CREATE TABLE IF NOT EXISTS families (
                    id UUID PRIMARY KEY,
                    name TEXT NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                CREATE TABLE IF NOT EXISTS children (
                    id UUID PRIMARY KEY,
                    family_id UUID NOT NULL REFERENCES families(id) ON DELETE CASCADE,
                    display_name TEXT NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                CREATE TABLE IF NOT EXISTS devices (
                    id UUID PRIMARY KEY,
                    child_id UUID NOT NULL REFERENCES children(id) ON DELETE CASCADE,
                    device_name TEXT NOT NULL,
                    platform TEXT NOT NULL DEFAULT 'android',
                    app_version TEXT,
                    last_seen_at TIMESTAMPTZ,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
                CREATE TABLE IF NOT EXISTS policies (
                    id UUID PRIMARY KEY,
                    family_id UUID NOT NULL REFERENCES families(id) ON DELETE CASCADE,
                    child_id UUID NOT NULL REFERENCES children(id) ON DELETE CASCADE,
                    version INTEGER NOT NULL,
                    policy_json JSONB NOT NULL,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    UNIQUE(child_id, version)
                );
                CREATE TABLE IF NOT EXISTS device_sync (
                    device_id UUID PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
                    applied_policy_version INTEGER NOT NULL DEFAULT 0,
                    last_sync_at TIMESTAMPTZ
                );
                CREATE TABLE IF NOT EXISTS time_requests (
                    id UUID PRIMARY KEY,
                    child_id UUID NOT NULL REFERENCES children(id) ON DELETE CASCADE,
                    device_id UUID REFERENCES devices(id) ON DELETE SET NULL,
                    package_name TEXT,
                    requested_minutes INTEGER NOT NULL CHECK (requested_minutes > 0 AND requested_minutes <= 120),
                    reason TEXT NOT NULL DEFAULT '',
                    status TEXT NOT NULL DEFAULT 'PENDING',
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                    responded_at TIMESTAMPTZ
                );
                ALTER TABLE time_requests ADD COLUMN IF NOT EXISTS package_name TEXT;
                ALTER TABLE time_requests ADD COLUMN IF NOT EXISTS approved_minutes INTEGER;
                ALTER TABLE time_requests ADD COLUMN IF NOT EXISTS consumed_minutes INTEGER DEFAULT 0;
                ALTER TABLE time_requests ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ;

                CREATE TABLE IF NOT EXISTS users (
                    id UUID PRIMARY KEY,
                    email TEXT UNIQUE NOT NULL,
                    password_hash TEXT NOT NULL,
                    family_id UUID REFERENCES families(id) ON DELETE CASCADE,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );

                CREATE TABLE IF NOT EXISTS pairing_codes (
                    code TEXT PRIMARY KEY,
                    family_id UUID NOT NULL REFERENCES families(id) ON DELETE CASCADE,
                    child_id UUID REFERENCES children(id) ON DELETE CASCADE,
                    expires_at TIMESTAMPTZ NOT NULL,
                    used BOOLEAN NOT NULL DEFAULT FALSE,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );

                CREATE TABLE IF NOT EXISTS schedules (
                    id UUID PRIMARY KEY,
                    child_id UUID NOT NULL REFERENCES children(id) ON DELETE CASCADE,
                    name TEXT NOT NULL,
                    start_time TEXT NOT NULL,
                    end_time TEXT NOT NULL,
                    days_of_week TEXT NOT NULL DEFAULT 'MON,TUE,WED,THU,FRI,SAT,SUN',
                    restricted_packages JSONB NOT NULL DEFAULT '[]'::jsonb,
                    enabled BOOLEAN NOT NULL DEFAULT TRUE,
                    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );

                CREATE TABLE IF NOT EXISTS device_locks (
                    child_id UUID PRIMARY KEY REFERENCES children(id) ON DELETE CASCADE,
                    locked BOOLEAN NOT NULL DEFAULT FALSE,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                );
            """)
        conn.commit()


class FamilyCreate(BaseModel):
    name: str = Field(min_length=1, max_length=100)


class ChildCreate(BaseModel):
    family_id: uuid.UUID
    display_name: str = Field(min_length=1, max_length=100)


class DeviceCreate(BaseModel):
    child_id: uuid.UUID
    device_name: str = Field(min_length=1, max_length=100)
    app_version: Optional[str] = None


class PolicyCreate(BaseModel):
    family_id: uuid.UUID
    child_id: uuid.UUID
    policy_json: dict


class SyncAck(BaseModel):
    applied_policy_version: int
    app_version: Optional[str] = None


class TimeRequestCreate(BaseModel):
    child_id: uuid.UUID
    device_id: Optional[uuid.UUID] = None
    package_name: Optional[str] = Field(default=None, max_length=200)
    requested_minutes: int = Field(gt=0, le=120)
    reason: str = Field(default='', max_length=300)


class TimeRequestDecision(BaseModel):
    status: str = Field(pattern='^(APPROVED|DECLINED)$')


class ConsumeAllowanceRequest(BaseModel):
    package_name: str
    consumed_minutes: int = Field(ge=0)


class PairingGenerateRequest(BaseModel):
    family_id: uuid.UUID
    child_id: uuid.UUID


class PairDeviceRequest(BaseModel):
    code: str
    device_name: str
    app_version: Optional[str] = "2.0.0"


class ScheduleCreate(BaseModel):
    child_id: uuid.UUID
    name: str
    start_time: str
    end_time: str
    days_of_week: str = "MON,TUE,WED,THU,FRI,SAT,SUN"
    restricted_packages: list[str]
    enabled: bool = True


@app.on_event("startup")
def startup():
    init_db()


@app.get("/health")
def health():
    with db() as conn:
        with conn.cursor() as cur:
            cur.execute("SELECT 1")
            cur.fetchone()
    return {"status": "ok", "service": "familycontrol-api", "version": "1.0.0"}


@app.post("/api/families")
def create_family(payload: FamilyCreate):
    family_id = uuid.uuid4()
    with db() as conn:
        with conn.cursor() as cur:
            cur.execute(
                "INSERT INTO families (id, name) VALUES (%s, %s)",
                (family_id, payload.name),
            )
        conn.commit()
    return {"family_id": str(family_id), "name": payload.name}


@app.post("/api/children")
def create_child(payload: ChildCreate):
    child_id = uuid.uuid4()
    with db() as conn:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT 1 FROM families WHERE id=%s", (payload.family_id,)
            )
            if cur.fetchone() is None:
                raise HTTPException(404, "Family not found")
            cur.execute(
                "INSERT INTO children (id, family_id, display_name) VALUES (%s,%s,%s)",
                (child_id, payload.family_id, payload.display_name),
            )
        conn.commit()
    return {"child_id": str(child_id), "family_id": str(payload.family_id)}


@app.post("/api/devices")
def register_device(payload: DeviceCreate):
    device_id = uuid.uuid4()
    now = datetime.now(timezone.utc)
    with db() as conn:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT 1 FROM children WHERE id=%s", (payload.child_id,)
            )
            if cur.fetchone() is None:
                raise HTTPException(404, "Child not found")
            cur.execute(
                """INSERT INTO devices
                   (id, child_id, device_name, platform, app_version, last_seen_at)
                   VALUES (%s,%s,%s,'android',%s,%s)""",
                (device_id, payload.child_id, payload.device_name,
                 payload.app_version, now),
            )
            cur.execute(
                "INSERT INTO device_sync (device_id) VALUES (%s)", (device_id,)
            )
        conn.commit()
    return {"device_id": str(device_id), "last_seen_at": now.isoformat()}


@app.post("/api/policies")
def create_policy(payload: PolicyCreate):
    with db() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """SELECT id, version, policy_json
                   FROM policies
                   WHERE child_id=%s
                   ORDER BY version DESC
                   LIMIT 1""",
                (payload.child_id,),
            )
            latest = cur.fetchone()
            if latest is not None and latest[2] == payload.policy_json:
                return {
                    "changed": False,
                    "policy_id": str(latest[0]),
                    "version": latest[1],
                    "message": "No policy changes to publish"
                }

            version = (latest[1] if latest else 0) + 1
            policy_id = uuid.uuid4()
            cur.execute(
                """INSERT INTO policies
                   (id,family_id,child_id,version,policy_json)
                   VALUES (%s,%s,%s,%s,%s)""",
                (policy_id, payload.family_id, payload.child_id,
                 version, psycopg.types.json.Json(payload.policy_json)),
            )
        conn.commit()
    return {"changed": True, "policy_id": str(policy_id), "version": version}


@app.get("/api/devices/{device_id}/sync")
def get_device_sync(device_id: uuid.UUID):
    with db() as conn:
        with conn.cursor() as cur:
            cur.execute("""
                SELECT d.child_id, c.family_id, s.applied_policy_version,
                       s.last_sync_at
                FROM devices d
                JOIN children c ON c.id=d.child_id
                JOIN device_sync s ON s.device_id=d.id
                WHERE d.id=%s
            """, (device_id,))
            row = cur.fetchone()
            if not row:
                raise HTTPException(404, "Device not found")

            child_id, family_id, applied, last_sync = row
            cur.execute("""
                SELECT version, policy_json, created_at
                FROM policies
                WHERE child_id=%s AND version > %s
                ORDER BY version DESC
                LIMIT 1
            """, (child_id, applied))
            policy = cur.fetchone()

    if not policy:
        return {
            "status": "SYNCED",
            "family_id": str(family_id),
            "child_id": str(child_id),
            "device_id": str(device_id),
            "applied_policy_version": applied,
            "policy": None,
            "last_sync_at": last_sync.isoformat() if last_sync else None,
        }

    version, policy_json, created_at = policy
    return {
        "status": "OUTDATED",
        "family_id": str(family_id),
        "child_id": str(child_id),
        "device_id": str(device_id),
        "applied_policy_version": applied,
        "available_policy_version": version,
        "policy": policy_json,
        "created_at": created_at.isoformat(),
    }


@app.post("/api/devices/{device_id}/sync-ack")
def acknowledge_sync(device_id: uuid.UUID, payload: SyncAck):
    now = datetime.now(timezone.utc)
    with db() as conn:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT 1 FROM device_sync WHERE device_id=%s", (device_id,)
            )
            if cur.fetchone() is None:
                raise HTTPException(404, "Device not found")
            cur.execute(
                """UPDATE device_sync
                   SET applied_policy_version=%s,last_sync_at=%s
                   WHERE device_id=%s""",
                (payload.applied_policy_version, now, device_id),
            )
            cur.execute(
                """UPDATE devices
                   SET app_version=%s,last_seen_at=%s
                   WHERE id=%s""",
                (payload.app_version, now, device_id),
            )
        conn.commit()
    return {
        "status": "SYNCED",
        "device_id": str(device_id),
        "applied_policy_version": payload.applied_policy_version,
        "last_sync_at": now.isoformat(),
    }


@app.post("/api/time-requests")
def create_time_request(payload: TimeRequestCreate):
    request_id = uuid.uuid4()
    with db() as conn:
        with conn.cursor() as cur:
            cur.execute("SELECT 1 FROM children WHERE id=%s", (payload.child_id,))
            if cur.fetchone() is None:
                raise HTTPException(404, "Child not found")
            cur.execute(
                """INSERT INTO time_requests
                   (id, child_id, device_id, package_name, requested_minutes, reason)
                   VALUES (%s,%s,%s,%s,%s,%s)""",
                (request_id, payload.child_id, payload.device_id,
                 payload.package_name, payload.requested_minutes, payload.reason),
            )
        conn.commit()
    return {"request_id": str(request_id), "status": "PENDING"}


@app.get("/api/time-requests/{child_id}")
def list_time_requests(child_id: uuid.UUID):
    with db() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """SELECT id, package_name, requested_minutes, reason, status, created_at, responded_at
                   FROM time_requests
                   WHERE child_id=%s
                   ORDER BY created_at DESC
                   LIMIT 20""",
                (child_id,),
            )
            rows = cur.fetchall()
    return {"requests": [
        {
            "request_id": str(r[0]),
            "package_name": r[1],
            "requested_minutes": r[2],
            "reason": r[3],
            "status": r[4],
            "created_at": r[5].isoformat(),
            "responded_at": r[6].isoformat() if r[6] else None,
        } for r in rows
    ]}


@app.get("/api/time-requests/{child_id}/approved-minutes-today")
def approved_minutes_today(child_id: uuid.UUID, package_name: Optional[str] = None):
    with db() as conn:
        with conn.cursor() as cur:
            if package_name:
                cur.execute(
                    """SELECT COALESCE(SUM(requested_minutes), 0)
                       FROM time_requests
                       WHERE child_id=%s
                         AND status='APPROVED'
                         AND package_name=%s
                         AND created_at::date = CURRENT_DATE""",
                    (child_id, package_name),
                )
            else:
                cur.execute(
                    """SELECT COALESCE(SUM(requested_minutes), 0)
                       FROM time_requests
                       WHERE child_id=%s
                         AND status='APPROVED'
                         AND package_name IS NULL
                         AND created_at::date = CURRENT_DATE""",
                    (child_id,),
                )
            total = cur.fetchone()[0] or 0
    return {
        "child_id": str(child_id),
        "package_name": package_name,
        "approved_minutes_today": int(total),
    }

@app.post("/api/time-requests/{request_id}/decision")
def decide_time_request(request_id: uuid.UUID, payload: TimeRequestDecision):
    now = datetime.now(timezone.utc)
    from datetime import timedelta
    expires_at = now + timedelta(hours=24)
    with db() as conn:
        with conn.cursor() as cur:
            if payload.status == "APPROVED":
                cur.execute(
                    """UPDATE time_requests
                       SET status=%s, responded_at=%s,
                           approved_minutes=requested_minutes,
                           consumed_minutes=COALESCE(consumed_minutes, 0),
                           expires_at=%s
                       WHERE id=%s AND status='PENDING'
                       RETURNING id, status, package_name, requested_minutes""",
                    (payload.status, now, expires_at, request_id),
                )
            else:
                cur.execute(
                    """UPDATE time_requests
                       SET status=%s, responded_at=%s
                       WHERE id=%s AND status='PENDING'
                       RETURNING id, status, package_name, requested_minutes""",
                    (payload.status, now, request_id),
                )
            row = cur.fetchone()
            if not row:
                raise HTTPException(404, "Pending request not found")
        conn.commit()
    return {
        "request_id": str(row[0]),
        "status": row[1],
        "package_name": row[2],
        "approved_minutes": row[3] if row[1] == "APPROVED" else 0,
        "responded_at": now.isoformat()
    }


@app.get("/api/time-requests/{child_id}/allowances")
def get_time_allowances(child_id: uuid.UUID, package_name: Optional[str] = None):
    now = datetime.now(timezone.utc)
    with db() as conn:
        with conn.cursor() as cur:
            if package_name:
                cur.execute(
                    """SELECT id, package_name, COALESCE(approved_minutes, requested_minutes),
                              COALESCE(consumed_minutes, 0), created_at, expires_at
                       FROM time_requests
                       WHERE child_id=%s
                         AND status='APPROVED'
                         AND package_name=%s
                         AND (expires_at IS NULL OR expires_at > %s)
                       ORDER BY created_at ASC""",
                    (child_id, package_name, now),
                )
            else:
                cur.execute(
                    """SELECT id, package_name, COALESCE(approved_minutes, requested_minutes),
                              COALESCE(consumed_minutes, 0), created_at, expires_at
                       FROM time_requests
                       WHERE child_id=%s
                         AND status='APPROVED'
                         AND (expires_at IS NULL OR expires_at > %s)
                       ORDER BY created_at ASC""",
                    (child_id, now),
                )
            rows = cur.fetchall()

    allowances = []
    for r in rows:
        approved = r[2] or 0
        consumed = r[3] or 0
        remaining = max(0, approved - consumed)
        allowances.append({
            "request_id": str(r[0]),
            "package_name": r[1],
            "approved_minutes": approved,
            "consumed_minutes": consumed,
            "remaining_minutes": remaining,
            "created_at": r[4].isoformat(),
            "expires_at": r[5].isoformat() if r[5] else None,
        })
    return {"child_id": str(child_id), "allowances": allowances}


@app.post("/api/time-requests/{child_id}/consume-allowance")
def consume_allowance(child_id: uuid.UUID, payload: ConsumeAllowanceRequest):
    now = datetime.now(timezone.utc)
    with db() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """SELECT id, COALESCE(approved_minutes, requested_minutes), COALESCE(consumed_minutes, 0)
                   FROM time_requests
                   WHERE child_id=%s
                     AND status='APPROVED'
                     AND package_name=%s
                     AND (expires_at IS NULL OR expires_at > %s)
                   ORDER BY created_at ASC""",
                (child_id, payload.package_name, now),
            )
            rows = cur.fetchall()

            remaining_to_consume = payload.consumed_minutes
            for r in rows:
                req_id, approved, consumed = r[0], r[1] or 0, r[2] or 0
                avail = max(0, approved - consumed)
                if avail <= 0:
                    continue
                to_add = min(avail, remaining_to_consume)
                new_consumed = consumed + to_add
                cur.execute(
                    "UPDATE time_requests SET consumed_minutes=%s WHERE id=%s",
                    (new_consumed, req_id),
                )
                remaining_to_consume -= to_add
                if remaining_to_consume <= 0:
                    break
        conn.commit()
    return {"status": "ok", "package_name": payload.package_name, "reported_consumed": payload.consumed_minutes}


@app.post("/api/pairing/generate")
def generate_pairing_code(payload: PairingGenerateRequest):
    import random, string
    from datetime import timedelta
    code = ''.join(random.choices(string.digits, k=6))
    now = datetime.now(timezone.utc)
    expires_at = now + timedelta(minutes=15)
    with db() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """INSERT INTO pairing_codes (code, family_id, child_id, expires_at)
                   VALUES (%s, %s, %s, %s)""",
                (code, payload.family_id, payload.child_id, expires_at),
            )
        conn.commit()
    return {"code": code, "expires_at": expires_at.isoformat()}


@app.post("/api/pairing/pair")
def pair_device(payload: PairDeviceRequest):
    now = datetime.now(timezone.utc)
    with db() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """SELECT family_id, child_id, used, expires_at
                   FROM pairing_codes
                   WHERE code=%s""",
                (payload.code.strip(),),
            )
            row = cur.fetchone()
            if not row:
                raise HTTPException(404, "Invalid pairing code")
            family_id, child_id, used, expires_at = row
            if used or expires_at < now:
                raise HTTPException(400, "Pairing code expired or already used")

            device_id = uuid.uuid4()
            cur.execute(
                """INSERT INTO devices (id, child_id, device_name, platform, app_version, last_seen_at)
                   VALUES (%s, %s, %s, 'android', %s, %s)""",
                (device_id, child_id, payload.device_name, payload.app_version, now),
            )
            cur.execute(
                "INSERT INTO device_sync (device_id) VALUES (%s)", (device_id,)
            )
            cur.execute(
                "UPDATE pairing_codes SET used=TRUE WHERE code=%s", (payload.code.strip(),)
            )
        conn.commit()
    return {
        "status": "PAIRED",
        "family_id": str(family_id),
        "child_id": str(child_id),
        "device_id": str(device_id)
    }


@app.post("/api/schedules")
def create_or_update_schedule(payload: ScheduleCreate):
    schedule_id = uuid.uuid4()
    with db() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """INSERT INTO schedules (id, child_id, name, start_time, end_time, days_of_week, restricted_packages, enabled)
                   VALUES (%s, %s, %s, %s, %s, %s, %s, %s)""",
                (
                    schedule_id,
                    payload.child_id,
                    payload.name,
                    payload.start_time,
                    payload.end_time,
                    payload.days_of_week,
                    psycopg.types.json.Json(payload.restricted_packages),
                    payload.enabled
                ),
            )
        conn.commit()
    return {"schedule_id": str(schedule_id), "status": "CREATED"}


@app.get("/api/schedules/{child_id}")
def get_schedules(child_id: uuid.UUID):
    with db() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """SELECT id, name, start_time, end_time, days_of_week, restricted_packages, enabled
                   FROM schedules
                   WHERE child_id=%s
                   ORDER BY created_at DESC""",
                (child_id,),
            )
            rows = cur.fetchall()
    return {
        "schedules": [
            {
                "id": str(r[0]),
                "name": r[1],
                "start_time": r[2],
                "end_time": r[3],
                "days_of_week": r[4],
                "restricted_packages": r[5],
                "enabled": r[6]
            }
            for r in rows
        ]
    }


@app.get("/api/analytics/insights/{child_id}")
def get_ai_insights(child_id: uuid.UUID):
    with db() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """SELECT COUNT(*), COALESCE(SUM(requested_minutes), 0)
                   FROM time_requests
                   WHERE child_id=%s""",
                (child_id,),
            )
            total_req, total_mins = cur.fetchone()

            cur.execute(
                """SELECT package_name, COUNT(*)
                   FROM time_requests
                   WHERE child_id=%s AND package_name IS NOT NULL
                   GROUP BY package_name
                   ORDER BY COUNT(*) DESC
                   LIMIT 1""",
                (child_id,),
            )
            top_pkg_row = cur.fetchone()

    insights = [
        {
            "insight_type": "ROUTINE_RECOMMENDATION",
            "headline": "Smart Routine Advisory",
            "body": "Consistent digital habits promote healthy bedtime routines. Consider configuring a 9:00 PM Bedtime schedule."
        }
    ]

    if total_req > 0:
        top_app = top_pkg_row[0] if top_pkg_row else "apps"
        insights.append({
            "insight_type": "USAGE_TREND",
            "headline": f"Time Request Trend for {top_app.split('.')[-1].capitalize()}",
            "body": f"Total {total_req} extra time requests ({total_mins} mins requested). Recommend reviewing evening app limits."
        })

class InstantLockRequest(BaseModel):
    locked: bool

@app.get("/api/children/{child_id}/instant-lock")
def get_instant_lock(child_id: uuid.UUID):
    with db() as conn:
        with conn.cursor() as cur:
            cur.execute("SELECT locked FROM device_locks WHERE child_id=%s", (child_id,))
            row = cur.fetchone()
            locked = row[0] if row else False
    return {"child_id": str(child_id), "locked": locked}

@app.post("/api/children/{child_id}/instant-lock")
def set_instant_lock(child_id: uuid.UUID, payload: InstantLockRequest):
    now = datetime.now(timezone.utc)
    with db() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """INSERT INTO device_locks (child_id, locked, updated_at)
                   VALUES (%s, %s, %s)
                   ON CONFLICT (child_id) DO UPDATE SET locked=EXCLUDED.locked, updated_at=EXCLUDED.updated_at""",
                (child_id, payload.locked, now)
            )
        conn.commit()
    return {"child_id": str(child_id), "locked": payload.locked, "updated_at": now.isoformat()}



