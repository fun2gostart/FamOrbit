import json
import logging
import os
import random
import string
import uuid
from datetime import datetime, timezone, timedelta
from typing import Optional

import psycopg
from fastapi import FastAPI, HTTPException
from fastapi.responses import HTMLResponse
from pydantic import BaseModel, Field

try:
    import firebase_admin
    from firebase_admin import credentials, messaging
    FIREBASE_SDK_AVAILABLE = True
except ImportError:
    FIREBASE_SDK_AVAILABLE = False
    firebase_admin = None
    credentials = None
    messaging = None

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("familycontrol-api")

DATABASE_URL = os.getenv(
    "DATABASE_URL",
    "postgresql://familycontrol:familycontrol@localhost:5432/familycontrol",
)

app = FastAPI(title="FamilyControl API", version="1.3.1")

# --- Firebase Admin Initialization ---
firebase_initialized = False


def init_firebase():
    global firebase_initialized
    if firebase_initialized:
        return
    if not FIREBASE_SDK_AVAILABLE:
        logger.warning("firebase-admin package not installed. Push notifications disabled.")
        return
    try:
        cred_json = os.getenv("FIREBASE_CREDENTIALS_JSON")
        cred_path = os.getenv("FIREBASE_CREDENTIALS_PATH")
        if cred_json:
            cred_dict = json.loads(cred_json)
            cred = credentials.Certificate(cred_dict)
            firebase_admin.initialize_app(cred)
            firebase_initialized = True
            logger.info("Firebase Admin successfully initialized from FIREBASE_CREDENTIALS_JSON")
        elif cred_path and os.path.exists(cred_path):
            cred = credentials.Certificate(cred_path)
            firebase_admin.initialize_app(cred)
            firebase_initialized = True
            logger.info("Firebase Admin successfully initialized from %s", cred_path)
        else:
            logger.warning("No Firebase credentials found. FCM push notifications disabled.")
    except Exception as e:
        logger.error("Failed to initialize Firebase Admin: %s", e)


def get_masked_db_url():
    url = os.getenv("DATABASE_URL")
    if not url:
        return "MISSING (defaulting to localhost:5432)"
    try:
        parts = url.split("@")
        if len(parts) == 2:
            prefix = parts[0].split(":")
            if len(prefix) > 2:
                masked_prefix = ":".join(prefix[:-1]) + ":***"
                return f"{masked_prefix}@{parts[1]}"
        return "CONFIGURED"
    except Exception:
        return "CONFIGURED"


def db():
    url = os.getenv("DATABASE_URL", DATABASE_URL)
    return psycopg.connect(url)


def init_db():
    import time
    url = os.getenv("DATABASE_URL", DATABASE_URL)
    logger.info("Connecting to database: %s", get_masked_db_url())
    for attempt in range(1, 6):
        try:
            with psycopg.connect(url) as conn:
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
                        ALTER TABLE devices ADD COLUMN IF NOT EXISTS fcm_token TEXT;
                        ALTER TABLE devices ADD COLUMN IF NOT EXISTS role TEXT DEFAULT 'CHILD';

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
            logger.info("Database schema initialized successfully.")
            return
        except Exception as e:
            logger.warning("DB init attempt %s/5 failed: %s", attempt, e)
            if attempt < 5:
                time.sleep(2)
            else:
                logger.error("Database connection could not be established on startup: %s. Continuing startup.", e)


# --- FCM Helper Functions ---
def send_fcm_push(tokens: list[str], data_payload: dict[str, str], title: Optional[str] = None, body: Optional[str] = None):
    if not firebase_initialized or not tokens:
        return
    clean_tokens = [t.strip() for t in tokens if t and t.strip()]
    if not clean_tokens:
        return
    str_data = {k: str(v) for k, v in data_payload.items() if v is not None}
    for token in clean_tokens:
        try:
            notification = None
            if title and body:
                notification = messaging.Notification(title=title, body=body)
            msg = messaging.Message(
                data=str_data,
                notification=notification,
                token=token,
            )
            response = messaging.send(msg)
            logger.info("[FCM] Sent message to %s...: %s", token[:12], response)
        except Exception as e:
            err_msg = str(e)
            if "not a valid FCM registration token" in err_msg or "Unregistered" in err_msg or "invalid-argument" in err_msg.lower():
                logger.warning("[FCM] Device token %s is expired or invalid (skipped)", token[:12])
                try:
                    with db() as conn:
                        with conn.cursor() as cur:
                            cur.execute("UPDATE devices SET fcm_token=NULL WHERE fcm_token=%s", (token,))
                        conn.commit()
                except Exception:
                    pass
            else:
                logger.error("[FCM] Send error for token %s...: %s", token[:12], e)


def get_child_device_tokens(child_id: uuid.UUID) -> list[str]:
    with db() as conn:
        with conn.cursor() as cur:
            cur.execute(
                "SELECT fcm_token FROM devices WHERE child_id=%s AND fcm_token IS NOT NULL AND fcm_token != ''",
                (child_id,)
            )
            rows = cur.fetchall()
            return [r[0] for r in rows if r[0]]


def get_parent_device_tokens(family_id: uuid.UUID) -> list[str]:
    with db() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """SELECT d.fcm_token 
                   FROM devices d
                   JOIN children c ON c.id = d.child_id
                   WHERE c.family_id=%s AND d.role='PARENT' AND d.fcm_token IS NOT NULL AND d.fcm_token != ''""",
                (family_id,)
            )
            rows = cur.fetchall()
            return [r[0] for r in rows if r[0]]


# --- Request/Response Models ---
class FamilyCreate(BaseModel):
    name: str = Field(min_length=1, max_length=100)


class ChildCreate(BaseModel):
    family_id: uuid.UUID
    display_name: str = Field(min_length=1, max_length=100)


class DeviceCreate(BaseModel):
    child_id: uuid.UUID
    device_name: str = Field(min_length=1, max_length=100)
    app_version: Optional[str] = None
    role: Optional[str] = "CHILD"
    fcm_token: Optional[str] = None


class FcmTokenUpdate(BaseModel):
    fcm_token: str
    role: Optional[str] = None


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


class InstantLockRequest(BaseModel):
    locked: bool


class EmergencyAlertRequest(BaseModel):
    message: Optional[str] = "🚨 SOS Emergency Alert from Parent"


@app.on_event("startup")
def startup():
    try:
        init_db()
    except Exception as e:
        logger.error("Database initialization error during startup: %s", e)
    try:
        init_firebase()
    except Exception as e:
        logger.error("Firebase initialization error during startup: %s", e)


@app.get("/health")
def health():
    db_ok = False
    try:
        with db() as conn:
            with conn.cursor() as cur:
                cur.execute("SELECT 1")
                cur.fetchone()
        db_ok = True
    except Exception as e:
        logger.warning("Health check DB probe failed: %s", e)

    return {
        "status": "ok" if db_ok else "degraded",
        "database": "connected" if db_ok else "disconnected",
        "service": "familycontrol-api",
        "version": "1.3.1",
        "firebase_enabled": firebase_initialized,
        "db_config": get_masked_db_url()
    }


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
                   (id, child_id, device_name, platform, app_version, last_seen_at, role, fcm_token)
                   VALUES (%s,%s,%s,'android',%s,%s,%s,%s)""",
                (device_id, payload.child_id, payload.device_name,
                 payload.app_version, now, payload.role or "CHILD", payload.fcm_token),
            )
            cur.execute(
                "INSERT INTO device_sync (device_id) VALUES (%s)", (device_id,)
            )
        conn.commit()
    return {"device_id": str(device_id), "last_seen_at": now.isoformat()}


@app.post("/api/devices/{device_id}/fcm-token")
def update_fcm_token(device_id: uuid.UUID, payload: FcmTokenUpdate):
    now = datetime.now(timezone.utc)
    with db() as conn:
        with conn.cursor() as cur:
            if payload.role:
                cur.execute(
                    """UPDATE devices
                       SET fcm_token=%s, role=%s, last_seen_at=%s
                       WHERE id=%s""",
                    (payload.fcm_token, payload.role, now, device_id),
                )
            else:
                cur.execute(
                    """UPDATE devices
                       SET fcm_token=%s, last_seen_at=%s
                       WHERE id=%s""",
                    (payload.fcm_token, now, device_id),
                )
            if cur.rowcount == 0:
                raise HTTPException(404, "Device not found")
        conn.commit()
    return {"status": "ok", "device_id": str(device_id), "fcm_token_updated": True}


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

    # Instant Push to Child Devices
    try:
        tokens = get_child_device_tokens(payload.child_id)
        if tokens:
            send_fcm_push(
                tokens=tokens,
                data_payload={
                    "action": "SYNC_POLICY",
                    "type": "SYNC_POLICY",
                    "version": str(version),
                    "child_id": str(payload.child_id)
                }
            )
    except Exception as e:
        logger.error("[FCM] Policy push error: %s", e)

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
            cur.execute("SELECT family_id, display_name FROM children WHERE id=%s", (payload.child_id,))
            child_row = cur.fetchone()
            if child_row is None:
                raise HTTPException(404, "Child not found")
            family_id, child_name = child_row[0], child_row[1]

            cur.execute(
                """INSERT INTO time_requests
                   (id, child_id, device_id, package_name, requested_minutes, reason)
                   VALUES (%s,%s,%s,%s,%s,%s)""",
                (request_id, payload.child_id, payload.device_id,
                 payload.package_name, payload.requested_minutes, payload.reason),
            )
        conn.commit()

    # Instant Push to Parent Devices
    try:
        parent_tokens = get_parent_device_tokens(family_id)
        if parent_tokens:
            send_fcm_push(
                tokens=parent_tokens,
                data_payload={
                    "action": "NEW_TIME_REQUEST",
                    "type": "NEW_TIME_REQUEST",
                    "request_id": str(request_id),
                    "child_name": child_name or "Child",
                    "minutes": str(payload.requested_minutes),
                    "package_name": payload.package_name or "",
                    "reason": payload.reason or ""
                },
                title="⏳ New Extra Time Request",
                body=f"{child_name} requested +{payload.requested_minutes}m"
            )
    except Exception as e:
        logger.error("[FCM] New time request push error: %s", e)

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
                       RETURNING id, status, package_name, requested_minutes, child_id""",
                    (payload.status, now, expires_at, request_id),
                )
            else:
                cur.execute(
                    """UPDATE time_requests
                       SET status=%s, responded_at=%s
                       WHERE id=%s AND status='PENDING'
                       RETURNING id, status, package_name, requested_minutes, child_id""",
                    (payload.status, now, request_id),
                )
            row = cur.fetchone()
            if not row:
                raise HTTPException(404, "Pending request not found")
        conn.commit()

    child_id = row[4]
    package_name = row[2] or ""
    minutes = row[3] if row[1] == "APPROVED" else 0

    # Instant Push to Child Devices
    try:
        child_tokens = get_child_device_tokens(child_id)
        if child_tokens:
            action = "TIME_REQUEST_APPROVED" if payload.status == "APPROVED" else "TIME_REQUEST_DECLINED"
            send_fcm_push(
                tokens=child_tokens,
                data_payload={
                    "action": action,
                    "type": action,
                    "request_id": str(request_id),
                    "package_name": package_name,
                    "minutes": str(minutes),
                },
                title="🎉 Extra Time Approved!" if payload.status == "APPROVED" else "❌ Extra Time Declined",
                body=f"Parent approved +{minutes}m" if payload.status == "APPROVED" else "Parent declined extra time request"
            )
    except Exception as e:
        logger.error("[FCM] Decision push error: %s", e)

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

    return {"child_id": str(child_id), "insights": insights}


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

    # Instant Push to Child Devices
    try:
        tokens = get_child_device_tokens(child_id)
        if tokens:
            send_fcm_push(
                tokens=tokens,
                data_payload={
                    "action": "INSTANT_LOCK",
                    "type": "INSTANT_LOCK",
                    "locked": str(payload.locked).lower(),
                    "child_id": str(child_id)
                }
            )
    except Exception as e:
        logger.error("[FCM] Instant lock push error: %s", e)

    return {"child_id": str(child_id), "locked": payload.locked, "updated_at": now.isoformat()}


@app.post("/api/children/{child_id}/emergency-alert")
def send_emergency_alert(child_id: uuid.UUID, payload: EmergencyAlertRequest):
    tokens = get_child_device_tokens(child_id)
    if tokens:
        send_fcm_push(
            tokens=tokens,
            data_payload={
                "action": "EMERGENCY_ALERT",
                "type": "EMERGENCY_ALERT",
                "message": payload.message or "🚨 SOS Emergency Alert from Parent"
            },
            title="🚨 SOS Emergency Alert",
            body=payload.message or "Immediate Attention Required"
        )
    return {"status": "sent", "child_id": str(child_id), "devices_notified": len(tokens)}


@app.delete("/api/families/{family_id}")
def delete_family(family_id: uuid.UUID):
    with db() as conn:
        with conn.cursor() as cur:
            cur.execute("DELETE FROM families WHERE id=%s", (family_id,))
            if cur.rowcount == 0:
                raise HTTPException(404, "Family not found")
        conn.commit()
    return {"status": "DELETED", "family_id": str(family_id), "message": "All family and child data erased permanently"}


@app.get("/privacy", response_class=HTMLResponse)
def privacy_policy():
    return """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>FamOrbit - Privacy Policy</title>
    <style>
        body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; line-height: 1.6; color: #1e293b; max-width: 800px; margin: 0 auto; padding: 24px 16px; background-color: #f8fafc; }
        .card { background: white; border-radius: 12px; padding: 32px; box-shadow: 0 4px 6px -1px rgb(0 0 0 / 0.1); border: 1px solid #e2e8f0; }
        h1 { color: #4338ca; margin-top: 0; font-size: 28px; }
        h2 { color: #1e1b4b; margin-top: 28px; font-size: 20px; border-bottom: 1px solid #e2e8f0; padding-bottom: 8px; }
        p, li { color: #334155; font-size: 15px; }
        .badge { display: inline-block; background: #e0e7ff; color: #3730a3; padding: 4px 10px; border-radius: 9999px; font-size: 12px; font-weight: 600; margin-bottom: 16px; }
        .highlight { background: #f1f5f9; border-left: 4px solid #6366f1; padding: 12px 16px; margin: 16px 0; border-radius: 0 8px 8px 0; }
        .footer { text-align: center; margin-top: 32px; font-size: 13px; color: #64748b; }
    </style>
</head>
<body>
    <div class="card">
        <span class="badge">FamOrbit Parental Control</span>
        <h1>Privacy Policy</h1>
        <p><strong>Last Updated:</strong> September 2026</p>
        
        <p>FamOrbit ("we", "our", or "us") is dedicated to protecting the privacy of children and families. This Privacy Policy explains how our parental control application collects, uses, and safeguards information when parents and children use our services.</p>

        <div class="highlight">
            <strong>Key Privacy Commitment:</strong> We do NOT sell user or children's personal data. We do NOT display third-party advertisements. Data is collected solely to provide parent-authorized screen time enforcement and safety features.
        </div>

        <h2>1. Children's Privacy (COPPA & GDPR-K Compliance)</h2>
        <p>FamOrbit is designed for parents to manage their children's digital well-being. In compliance with the Children's Online Privacy Protection Act (COPPA) and General Data Protection Regulation (GDPR-K):</p>
        <ul>
            <li>Data collected from child devices is strictly controlled by and visible only to the verified parent device in the same family group.</li>
            <li>We do not collect names of children's contacts, photos, microphone audio, camera feeds, or personal messages.</li>
            <li>Parents may view, modify, or permanently delete their child's profile and data at any time.</li>
        </ul>

        <h2>2. Information We Collect</h2>
        <ul>
            <li><strong>Device Identifiers & Pairing Codes:</strong> Anonymous device IDs, device models, and 6-digit pairing codes to link parent and child devices securely.</li>
            <li><strong>Application Usage Telemetry:</strong> Package names and duration of app usage to compute daily screen time limits and category budgets.</li>
            <li><strong>Push Notification Tokens (FCM):</strong> Device registration tokens used exclusively to send real-time policy updates, instant remote pause commands, and extra time approval notifications.</li>
        </ul>

        <h2>3. Permissions Used and Explicit Disclosures</h2>
        <p>To provide essential parental control and safety guard functionality, FamOrbit requests specific Android system permissions:</p>
        <ul>
            <li><strong>Accessibility Service (BIND_ACCESSIBILITY_SERVICE):</strong> Used exclusively to detect when a parent-restricted application enters the foreground, allowing FamOrbit to display limit-reached screens or enforce bedtime routines. FamOrbit <em>never</em> captures keystrokes, personal communications, or banking credentials.</li>
            <li><strong>Usage Access (PACKAGE_USAGE_STATS):</strong> Used to calculate daily app usage metrics and display screen time breakdown charts to parents.</li>
            <li><strong>Device Administration / Overlay:</strong> Used on child devices with parent consent to prevent unauthorized app tampering or uninstallation without the parent's PIN.</li>
            <li><strong>Notifications:</strong> Used to alert parents of extra time requests and alert children when parents grant extra time.</li>
        </ul>

        <h2>4. Data Security & Storage</h2>
        <p>All data transmitted between FamOrbit mobile devices and our cloud servers is encrypted in transit using industry-standard TLS/HTTPS protocols. Data is stored on secure, authenticated PostgreSQL infrastructure.</p>

        <h2>5. Account & Data Deletion</h2>
        <p>Parents retain full ownership of their data. You can permanently delete your family account and all associated child profiles, device tokens, and usage logs at any time directly within the FamOrbit app via <em>Parent Settings &gt; Delete Family Account</em>, or by contacting our support team. Deletion is instantaneous and permanent.</p>

        <h2>6. Contact Us</h2>
        <p>If you have any questions, concerns, or requests regarding this Privacy Policy or your family's data, please reach out to us at: <strong>support@famorbit.app</strong>.</p>
    </div>
    <div class="footer">
        &copy; 2026 FamOrbit. All rights reserved. • <a href="/terms" style="color: #6366f1;">Terms of Service</a>
    </div>
</body>
</html>"""


@app.get("/terms", response_class=HTMLResponse)
def terms_of_service():
    return """<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>FamOrbit - Terms of Service</title>
    <style>
        body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; line-height: 1.6; color: #1e293b; max-width: 800px; margin: 0 auto; padding: 24px 16px; background-color: #f8fafc; }
        .card { background: white; border-radius: 12px; padding: 32px; box-shadow: 0 4px 6px -1px rgb(0 0 0 / 0.1); border: 1px solid #e2e8f0; }
        h1 { color: #4338ca; margin-top: 0; font-size: 28px; }
        h2 { color: #1e1b4b; margin-top: 28px; font-size: 20px; border-bottom: 1px solid #e2e8f0; padding-bottom: 8px; }
        p, li { color: #334155; font-size: 15px; }
        .badge { display: inline-block; background: #e0e7ff; color: #3730a3; padding: 4px 10px; border-radius: 9999px; font-size: 12px; font-weight: 600; margin-bottom: 16px; }
        .footer { text-align: center; margin-top: 32px; font-size: 13px; color: #64748b; }
    </style>
</head>
<body>
    <div class="card">
        <span class="badge">FamOrbit Parental Control</span>
        <h1>Terms of Service</h1>
        <p><strong>Last Updated:</strong> September 2026</p>
        
        <h2>1. Acceptance of Terms</h2>
        <p>By downloading, installing, or using FamOrbit, you agree to be bound by these Terms of Service. If you do not agree, please do not use the application.</p>

        <h2>2. Permitted Use</h2>
        <p>FamOrbit is intended strictly for parents and legal guardians to monitor and manage digital screen time on devices owned by or provided to their minor children with lawful parental authority.</p>

        <h2>3. Parental Responsibilities</h2>
        <p>Parents are responsible for configuring screen time limits, maintaining the security of their parent PIN, and discussing digital rules with their children.</p>

        <h2>4. Disclaimers</h2>
        <p>FamOrbit provides tools to assist in digital well-being. While we strive for high reliability and anti-tamper security, FamOrbit does not guarantee uninterrupted service under all OEM Android battery-saver conditions.</p>

        <h2>5. Contact</h2>
        <p>For questions regarding these Terms, contact <strong>support@famorbit.app</strong>.</p>
    </div>
    <div class="footer">
        &copy; 2026 FamOrbit. All rights reserved. • <a href="/privacy" style="color: #6366f1;">Privacy Policy</a>
    </div>
</body>
</html>"""

