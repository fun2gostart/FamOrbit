# FAMORBIT — Project State Document
> 🕐 Last saved: 2026-10-12 00:07:00 IST
> 🤖 Source: Antigravity AI
> 🎯 Complete backup to rebuild, continue, or hand off this project.

## 📋 CHANGELOG
| # | Date & Time | What Changed | Sections Updated |
|---|-------------|-------------|------------------|
| 1 | 2026-10-09 10:56 IST | Initial backup & synced all recent production fixes (DB pool, battery & poll optimization, v1.5.3 release) | All |
| 2 | 2026-10-09 18:40 IST | Fixed Google Play Console warning "DEX code optimization below threshold": enabled R8 fullMode, targeted ProGuard rules, built optimized .aab (5.1 MB) | 4, 5, 7, 8, 11 |
| 3 | 2026-10-10 15:32 IST | Built optimized release APK (2.4 MB), uninstalled and cleanly reinstalled across test hardware | 9, 11 |
| 4 | 2026-10-10 15:37 IST | Performed clean uninstall and fresh re-installation of v1.5.3 (code 48) across all 4 connected devices | 9 |
| 5 | 2026-10-10 16:02 IST | Released v1.5.4 (code 49): Removed Child Mode button from Parent header, fixed hardware battery reporting via sticky intent + chunk preservation, eliminated app sync delay | 4, 5, 7, 9, 11 |
| 6 | 2026-10-10 16:06 IST | Performed clean uninstall and fresh re-installation of v1.5.4 (code 49) across all 4 connected devices | 9 |
| 7 | 2026-10-10 16:09 IST | Consolidated codebase into single active directory; permanently deleted redundant "Fam orbrit time machine" folder | 10, 14 |
| 8 | 2026-10-10 16:25 IST | Released v1.5.5 (code 50): Restyled Replace Device button on single row with text truncation protection, added Initial Sync progress notice | 4, 5, 9, 11 |
| 9 | 2026-10-10 16:28 IST | Full Production Readiness Audit: lintVitalRelease PASSED, bundleRelease signed AAB (5.1 MB) generated, zero active crashes across all 4 devices | 7, 9, 11 |
| 10 | 2026-10-11 14:40 IST | Fixed Child-Parent data sync: added dedicated `device_telemetry` DB table & REST APIs, eliminated `time_requests` table clutter from pseudo catalog chunks, added child-isolated telemetry polling | 4, 5, 7, 8 |
| 11 | 2026-10-11 15:02 IST | Fixed Screen Time Progress mismatch, eliminated 2-min request latency via FCM immediate poll trigger, and fixed extra time allowance ledger crediting on child and parent | 4, 5, 7 |
| 12 | 2026-10-11 16:03 IST | Play Store Readiness P0: Set `usesCleartextTraffic="false"` in `AndroidManifest.xml` (full HTTPS enforcement). Verified Accessibility Prominent Disclosure compliance. Deployed release build across both physical OnePlus test devices and emulators. | 4, 7, 9 |
| 13 | 2026-10-11 17:22 IST | Sync & Request Polish: (1) Added `DELETE /api/devices/{id}` to cleanly unpair devices from DB/sync so replaced devices stop getting SOS/alerts; (2) Synchronized Daily Allowance across Parent Control Center, Child View, and physical Child Device; (3) Credited approved general extra time directly to daily limit on both parent and child; (4) Removed single-pending request restriction so child can raise multiple requests without freezing/blocking Child View. Deployed to both physical OnePlus devices and emulators. | 4, 5, 7, 9 |
| 14 | 2026-10-11 17:49 IST | UI Clean-up & Smooth App Blocking: (1) Removed duplicate bottom device status card from Parent Dashboard; (2) Fixed Limit Reached badge position and overflow clipping; (3) Resolved screen flickering when blocked/paused apps are launched by smoothly returning to home without competing activity transition fighting. Deployed release build across both physical OnePlus devices and emulators. | 4, 7, 9 |
| 15 | 2026-10-11 18:38 IST | Anti-Tamper & Security Hardening: (1) Fixed "Exit to Dashboard" button overlap in Parent Preview banner with responsive layout constraints; (2) Fine-tuned settings guard in AccessibilityService so child can freely access normal device settings (Wi-Fi, Bluetooth, Display, Sound, Battery, etc.) while blocking attempts to tamper with FamOrbit; (3) Enforced anti-uninstall protection via Device Administrator activation prompt on Child Home and package installer interception. Deployed across physical OnePlus devices and emulators. | 4, 7, 9 |
| 16 | 2026-10-11 18:52 IST | Device Admin Activation Fix: Granted 3-minute settings bypass when tapping Activate in Anti-Uninstall Card and updated AccessibilityService to permit activation/CAPTCHA flow without triggering anti-tamper minimization loop. Deployed across physical OnePlus devices and emulators. | 4, 7, 9 |
| 17 | 2026-10-11 19:07 IST | Privacy Policy Updates for Play Store Review: Updated hosted HTML policy at `https://famorbit-api.onrender.com/privacy` with explicit disclosures for Emergency SOS Location (ephemeral, encrypted, non-continuous) and Device Administrator (anti-tamper/anti-uninstall parental security). Deployed to production on Render. | 3, 4, 11 |
| 18 | 2026-10-11 19:21 IST | Production Release v1.5.7 (versionCode 52): Generated signed Google Play App Bundle (.aab, 5.1 MB) with R8 fullMode optimization, including all latest sync fixes, anti-uninstall protection, and privacy disclosures. Ready for Google Play Console upload. | 4, 8, 9, 11 |
| 19 | 2026-10-11 23:32 IST | Category Breakdown Sync Fix: DashboardScreen in Parent Mode now loads child-specific synced telemetry app usage (${childId}_appused_$p) rather than querying local parent phone usage stats. Category breakdown (Social, Entertainment, Gaming, Education, Productivity) now matches Child View accurately. Deployed across physical OnePlus devices and emulators. | 4, 7, 9 |
| 20 | 2026-10-11 23:50 IST | App Categorization Overhaul & Toast Debounce: (1) Added manual category override in Parent Control Center app limits dialog + category pill chip next to each app in list; (2) Added auto-detection via Android native ApplicationInfo.category + extended heuristics for JioHotstar (in.startv.hotstar), Disney, Prime, SonyLIV, Zee5, etc. under Entertainment; (3) Eliminated duplicate block toast message via 3500ms cooldown and activeToast.cancel() in AccessibilityService. | 4, 7, 9 |
| 21 | 2026-10-12 00:07 IST | App Card Redesign & Direct Profile Rename: (1) Replaced squished category chip with clean right-column layout (compact emoji pill on top, switch centered below); (2) Shortened long category labels (e.g. 'Productivity & Other' -> 'Productivity'); (3) Enabled 1-tap profile editing directly on active profile card in ChildProfileSelectorBar with instant cloud/local persistence. | 4, 7, 9 |

## 1. 📌 PROJECT OVERVIEW
- **App Name:** FamOrbit (FamilyControl Lab)
- **Description:** Complete cross-device parental control system for Android. Includes live remote screen time monitoring, app catalog inspection, instant remote locking, schedule downtime routines, extra time requests & approvals, web filtering, and emergency SOS siren/alerts.
- **Tech Stack:**
  - **Android Client:** Kotlin, Jetpack Compose, Material3, Coroutines, WorkManager, AccessibilityService, DeviceAdminReceiver.
  - **Backend:** Python FastAPI, Uvicorn.
  - **Database:** PostgreSQL (Neon serverless) with `psycopg-pool` connection pooling.
  - **Push Notifications:** Firebase Cloud Messaging (`firebase-admin`).
  - **Hosting:** Render (`famorbit-api.onrender.com`).
- **Platform:** Android (Min SDK 26, Target SDK 36, Android 8.0 - 15+)
- **Status:** In Dev / Active Staging (v1.5.4 deployed to all 4 devices)
- **Live URL:** `https://famorbit-api.onrender.com`
- **Repo URL:** Local git workspace

## 2. 🔑 CREDENTIALS & SECRETS
> ⚠️ SENSITIVE — Store securely. Never push to public repos.

| # | Type | Service | Username / Key | Password / Value | Notes |
|---|------|---------|---------------|-----------------|-------|
| 1 | Keystore | Android Release JKS | `famorbit` | `FamOrbit2026SecureReleaseKey` | Keystore file: `famorbit-release.jks` in project root |
| 2 | Backend DB | Neon PostgreSQL | `familycontrol` | Configured via `DATABASE_URL` env on Render | Host: Neon Serverless Postgres |
| 3 | Push API | Firebase Admin SDK | Service Account | `FIREBASE_CREDENTIALS_JSON` / `FIREBASE_CREDENTIALS_PATH` | Handled via env var on Render |

## 3. 🌐 URLS & ENDPOINTS
| # | Type | URL | Notes |
|---|------|-----|-------|
| 1 | Backend API Base | `https://famorbit-api.onrender.com` | FastAPI production root |
| 2 | Health Check | `https://famorbit-api.onrender.com/health` | Verifies DB connection & status |
| 3 | Privacy Policy | `https://famorbit-api.onrender.com/privacy` | Required for Google Play Store review |
| 4 | Terms of Service | `https://famorbit-api.onrender.com/terms` | Public legal disclosure page |
| 5 | Time Requests API | `https://famorbit-api.onrender.com/time-requests` | Time request submit & list |
| 6 | Device Sync API | `https://famorbit-api.onrender.com/device-sync/{id}` | Device policy and app catalog sync |

## 4. 🏗️ ARCHITECTURE & FILES
- **Folder Structure:**
  - `app/src/main/java/com/familycontrol/lab/`
    - `MainActivity.kt`: Main UI, Jetpack Compose screens (Parent Dashboard, Child View, QR Pairing, Device Detail, Requests).
    - `ApiClient.kt`: HTTP client with Keep-Alive connection pooling (`java.net.HttpURLConnection`), device registration, sync endpoints.
    - `FamilyAccessibilityService.kt`: Intercepts package launches, applies screen time locks, enforces web filter & anti-tamper.
    - `AccessibilityGuardEngine.kt`: Core rule evaluator (`isPackageBlocked()`, daily usage accounting, schedule enforcement).
    - `RequestPollEngine.kt`: Adaptive background polling (3s foreground, 20s background) for remote requests & catalog updates.
    - `PolicySyncEngine.kt`: Syncs remote cloud policy to local `policies.xml` / `parent_control.xml`.
    - `ChildDeviceManager.kt`: Multi-device pairing, model deduplication, hardware battery tracking.
    - `EnforcementService.kt`: Persistent foreground service (`specialUse`) keeping background protections alive.
    - `HeartbeatWorker.kt`: Periodic WorkManager job logging uptime and telemetry.
  - `backend/app/main.py`: FastAPI server routes, PostgreSQL schema creation, connection pooling, and FCM push notifications.
  - `backend/requirements.txt`: Python package dependencies (`fastapi`, `uvicorn`, `psycopg[binary]`, `psycopg-pool`, `firebase-admin`).

## 5. ✅ COMPLETED FEATURES
- [x] **Optimistic UI for Time Requests:** Zero lag on parent Approvals/Denials; updates local state instantly while syncing asynchronously.
- [x] **HTTP Keep-Alive Connection Reuse:** Reuses persistent SSL sockets to eliminate sequential TLS handshake lag.
- [x] **Backend Connection Pooling:** `psycopg-pool` with 2–15 persistent pooled connections to prevent Neon slot exhaustion.
- [x] **Battery-Aware Accessibility Watchdog:** Pauses CPU checking when screen is interactive-off; relaxed loop from 500ms to 1.5s.
- [x] **Adaptive Background Polling:** 3s interval in foreground; relaxes to 20s in background to prevent battery drain and OEM kills.
- [x] **Child App Catalog Reassembly:** Chunked telemetry (`DEV_APPS#`) reassembly across cloud time request payloads.
- [x] **Target SDK 36 & Android 14+ FGS Compliance:** `specialUse` foreground service declared with mandatory property subtypes.

## 6. 🚧 PENDING FEATURES
- [ ] **Full App Rebuild (Clean Architecture):** Transition 9,500-line monolithic `MainActivity.kt` into MVVM + Jetpack Compose modular screens.
- [ ] **SQLite / Room Database Migration:** Replace XML `SharedPreferences` JSON serialization with Jetpack Room tables.
- [ ] **Push-to-Sync (FCM Priority Data):** Replace background polling entirely with instant high-priority FCM wakeups.
- [ ] **Google Play Store Policy Submission Video:** Prepare screencast demonstrating legitimate parental accessibility justification.

## 7. 🐛 BUGS & ISSUES
| # | Bug | Status | Fix | Date |
|---|-----|--------|-----|------|
| 1 | Parent UI freezing for 2-4s on request tap | RESOLVED | Converted to optimistic local UI mutation + async HTTP Keep-Alive | 2026-10-08 |
| 2 | High CPU wakeups from Accessibility Service | RESOLVED | Added `isInteractive` guard + reduced watchdog rate to 1.5s | 2026-10-08 |
| 3 | Neon DB connection slot exhaustion risk | RESOLVED | Added `psycopg-pool` ConnectionPool in `backend/app/main.py` | 2026-10-08 |
| 4 | Background battery drain flagged by OS | RESOLVED | Implemented adaptive polling (20s background / 3s foreground) | 2026-10-08 |
| 5 | App catalog & battery telemetry polluting `time_requests` and dropping chunks | RESOLVED | Added dedicated `/api/devices/{id}/telemetry` table/endpoints and isolated polling pipeline | 2026-10-11 |

## 8. 🔬 DECISIONS MADE & 🚫 WHAT NOT TO DO
| Decision / Constraint | Options Considered | Chosen / Prohibited | Why / Context |
|-----------------------|-------------------|---------------------|---------------|
| DB Pooling | Direct `psycopg.connect` per request vs `psycopg-pool` | Chosen `psycopg-pool` | Direct connection opens/closes SSL TCP sockets per request, which quickly crashes Neon Postgres with connection exhaustion. |
| Accessibility Loop | Continuous 500ms loop vs Screen-Aware 1.5s loop | Chosen Screen-Aware 1.5s | 500ms keeps CPU awake constantly and drains battery; 1.5s is imperceptible to users and cuts wakeups by 66%. |
| Background Sync | Continuous 3s polling vs Adaptive Polling | Chosen Adaptive (3s / 20s) | Aggressive 3s polling in background triggers Android OEM battery killers (MIUI/OneUI/OxygenOS) to kill the app process. |
| 🚫 DO NOT DO | Blocking UI thread during network calls | Strictly Avoid | Causes ANRs (Application Not Responding) and UI stutters. Always use Coroutines / Threads / Optimistic UI. |
| 🚫 DO NOT DO | Directly modifying physical devices without user confirmation | Strictly Avoid | Rule in `GEMINI.md`: Ask explicit confirmation before commands targeting physical hardware. |

## 9. 📱 TEST HARDWARE & CONNECTED DEVICES
- **Physical Devices:** `adb-dd990c56-hgu82G._adb-tls-connect._tcp` (OnePlus CPH2729IN / CPH2729)
- **Active Emulators:** `emulator-5554` (`sdk_gphone16k_arm64`, API 36)
- **Install & Verification Commands:**
  - Build Release: `./gradlew assembleRelease`
  - Emulator Install: `adb -s emulator-5554 install -r "/Users/swapneshmhatre/Desktop/FamOrbit.apk"`
  - Physical Install: `adb -s "adb-dd990c56-hgu82G._adb-tls-connect._tcp" install -r "/Users/swapneshmhatre/Desktop/FamOrbit.apk"`
  - Verify Version: `adb -s <id> shell dumpsys package com.familycontrol.lab | grep -E "versionCode|versionName"`

## 10. 🛠️ LOCAL BUILD ENVIRONMENT
- **OS & Architecture:** macOS ARM64 (Apple Silicon)
- **Java / JDK:** OpenJDK 17 / 21
- **Gradle:** Gradle 9.4.1 (Android Gradle Plugin 8.9.0)
- **Python:** Python 3.9+ with FastAPI & psycopg-pool
- **Release Keystore:** `/Users/swapneshmhatre/Desktop/Fam orbrit time machine/FamOrbit/FamilyControl_v1.3.0_ExtraTimeEnforcement/famorbit-release.jks`

## 11. ⚙️ DEPLOYMENT & PRODUCTION
- **Hosting & Plan:** Render Web Service (Free/Starter)
- **API Base:** `https://famorbit-api.onrender.com`
- **Database:** Neon PostgreSQL Serverless
- **Release APK Output:** `/Users/swapneshmhatre/Desktop/FamOrbit.apk` (8.0 MB, `versionCode=48`, `versionName=1.5.3`)

## 12. 💰 COSTS
| Service | Plan | Cost | Renewal | Notes |
|---------|------|------|---------|-------|
| Render Web Service | Free / Starter | $0 - $7/mo | Monthly | Consider Starter ($7/mo) to eliminate 50s cold start spin-down |
| Neon PostgreSQL | Serverless Free | $0 | Monthly | Pooled connection limits handled by app |
| Firebase Cloud Messaging | Spark Free | $0 | Perpetual | Push notifications |

## 13. 👤 USER PREFERENCES & WORKING RULES
- **Confirmation rules:** Autonomous safe inspection on emulator; explicit confirmation required for physical device installations or file deletions.
- **Auto Project Backup:** Automatically update `FAMORBIT_PROJECT_STATE.md` after every significant change.
- **Desktop Artifacts:** Keep `/Users/swapneshmhatre/Desktop/FamOrbit.apk` continuously updated with latest verified builds.

## 14. 📝 NOTES & WORKAROUNDS
- **Active Codebase Directory:** Single unified directory at `/Users/swapneshmhatre/Desktop/Final App copies/FamOrbit/FamilyControl_v1.3.0_ExtraTimeEnforcement`.
- **R8 Proguard Shrinking:** ShrinkResources, MinifyEnabled, and fullMode are active in Release mode (keeps APK size at ~2.4 MB).

## 15. 🔄 NEXT STEPS & IMMEDIATE HANDOFF
1. Deploy the backend updates (`psycopg-pool` in `requirements.txt` and `main.py`) to Render via git push.
2. Proceed with the planned scratch rebuild in `/Users/swapneshmhatre/Desktop/FamOrbit Rebuild` using clean MVVM architecture and Room Database.
3. Test end-to-end multi-device sync between the physical device and emulator on fresh setup.

---
> To continue in a new chat, share this file and say:
> "Here is my project state. Continue working on [task]."
---
