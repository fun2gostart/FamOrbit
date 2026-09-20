# FamilyControl API v1.0.0

FastAPI + PostgreSQL development backend.

Host port: 8001 (container port 8000).

Endpoints include:
- GET /health
- POST /api/families
- POST /api/children
- POST /api/devices
- POST /api/policies
- GET /api/devices/{device_id}/sync
- POST /api/devices/{device_id}/sync-ack
- POST /api/time-requests
- GET /api/time-requests/{child_id}
- POST /api/time-requests/{request_id}/decision

Policy creation is change-aware: if the latest policy JSON is identical, the API returns the existing version instead of creating a duplicate.
