# FamOrbit (FamilyControl) — Project State & Documentation

**Project Name:** FamOrbit (FamilyControl)  
**Package:** `com.familycontrol.lab`  
**Current Version:** `1.6.8` (`versionCode 63`)  
**Workspace Directory:** `/Users/swapneshmhatre/Desktop/Final App copies/FamOrbit/FamilyControl_v1.3.0_ExtraTimeEnforcement`  
**Last Updated:** October 17, 2026  

---

## 1. Overview & Architecture

FamOrbit is a dual-role (Parent / Child) digital wellbeing and parental control Android application built using Jetpack Compose, Kotlin coroutines, Android Accessibility Service, and a Render-hosted Python/FastAPI cloud backend.

### Key Roles
- **Parent Device (`ROLE_PARENT`):** Remote dashboard and control center. Publishes screen time policies, sets app limits, configures focus presets (Focus, Bedtime, Dinner), grants extra time requests, and reviews synced telemetry. Does **not** require device-level accessibility or overlay permissions.
- **Child Device (`ROLE_CHILD`):** Enforced hardware target. Measures app usage via Android `UsageStatsManager`, enforces limits via `AccessibilityService` (`AccessibilityGuardEngine`), displays lock/timeout screens via System Alert Overlays, and synchronizes real-time telemetry with the parent phone through cloud endpoints.

---

## 2. Cloud Backend & Pairing Architecture

- **Backend Base URL:** `https://famorbit-api.onrender.com`
- **Pairing Flow:**
  1. Parent phone generates a 6-digit numeric pairing code (e.g. `482-910`) via `POST /api/pairing/generate`.
  2. Cloud backend binds the 6-digit code to the parent's `family_id` (UUID) and child's `child_id` (UUID) with a 15-minute expiration.
  3. Child enters the code into `ChildPairingScreen` via `POST /api/pairing/pair`.
  4. Backend assigns a `device_id` and binds the child device directly to the family profile.
  5. Child phone immediately publishes initial app catalog and hardware telemetry (`DEV_APPS#...` chunks via `POST /api/time-requests` and `POST /api/policies`).
  6. Parent phone discovers the new device and synchronizes app telemetry via `RequestPollEngine` (`GET /api/time-requests`).

---

## 3. Key Issues Resolved

### A. HTTP 422 "Publish failed — Network error" & Invalid Pairing Code
- **Root Cause:** When a fresh child profile was created locally on the parent phone, it was assigned a placeholder ID (`"child_default"`). When the parent called `POST /api/policies` or `POST /api/pairing/generate`, the FastAPI backend rejected `"child_default"` because it required a strict RFC UUID format (`UUID4`).
- **Fix in `ChildProfileManager.kt` & `ApiClient.kt`:**
  - Added `ChildProfileManager.updateChildProfileId(context, oldId, newId, newPairingCode)` which re-keys the profile in the internal list, writes back to disk (`child_profiles.json`), and updates `active_child_id` preference.
  - Updated `ApiClient.createServerPolicy` and `ApiClient.generatePairingCodeForChild` to detect non-UUID IDs, auto-register the child with the cloud backend to obtain a valid UUID, and seamlessly update local preferences.

### B. 30-Second Pairing Discovery Delay
- **Root Cause:** `RequestPollEngine` had a 10-second polling interval and lacked an on-demand trigger when a child finished pairing.
- **Fix:**
  - Reduced background poll interval in `RequestPollEngine.kt` to 3 seconds (`1, 3, TimeUnit.SECONDS`).
  - Added `RequestPollEngine.triggerImmediatePoll(context)` executed as soon as the pairing dialog is dismissed in `DashboardScreen` and `ParentHomeScreen`.

### C. Anti-Uninstall & Device Administrator Lockout (v1.6.2)
- **Root Cause:** Device administrator and accessibility anti-tamper protection locked out parents when trying to perform maintenance or uninstall without an on-device mechanism.
- **Fix:** Added on-device Parent PIN dialog (`showManageProtectionDialog`) supporting 15-minute maintenance mode, full anti-uninstall deactivation, and direct uninstall; added backend remote parent FCM command (`POST /api/children/{id}/anti-uninstall`); auto-deactivates admin on device unpair.

### D. Zero-Device Screen Time & Unpair Telemetry Cleanup (v1.6.3)
- **Root Cause:** When an active device was unpaired from a child profile, old cached screen time and app catalog remained in local state, causing the profile to falsely show "Used: 4h 3m" and "Limit Reached" even with 0 devices.
- **Fix:** Enforced zero-device awareness across `DashboardScreen`, `DailyBalanceResolver`, and `ChildHomeScreen`; added `ChildDeviceManager.clearChildTelemetry` to purge stale telemetry immediately on unpair or replacement.

### E. Restricted App Launch Flickering Loop & Trend Alignment (v1.6.7)
- **Root Cause:** When a restricted app was opened on the child device, accessibility service attempted to close it while the system launcher was animating, triggering rapid cyclical accessibility events back-and-forth. Additionally, parent and child 7-day usage trends calculated totals slightly differently.
- **Fix:** Added `blockedPackageInFlight` debounce in `FamilyAccessibilityService.kt` to suppress repeated triggers while the launcher returns home; aligned 7-day usage aggregation across parent dashboard and child home screen.

### F. Split-Brain Accounting & Ground-Truth Usage Reconciliation (v1.6.8)
- **Root Cause:** Apps like StorageScope were falsely blocked ("limit reached") despite the UI displaying 28 minutes remaining extra time. The in-memory tracking session accumulated phantom time while the phone screen was locked/off, drifting far ahead of the actual ground truth.
- **Fix:**
  - Implemented self-healing reconciliation in `AccessibilityGuardEngine.kt` that clamps `accumulatedMs` against Android OS `UsageStatsManager` ground truth (+ 2 min live session margin).
  - Registered dynamic `BroadcastReceiver` in `FamilyAccessibilityService.kt` for `ACTION_SCREEN_OFF` and `ACTION_USER_PRESENT` to halt foreground session accumulation immediately when the screen locks.
  - Added `clearCorruptedAccumulatedUsage(context)` startup routine in `MainActivity.kt` to purge stale millisecond values on app launch.

---

## 4. UX & Transparency Enhancements (Recent)

### A. 10–15 Seconds Pairing Time Expectation
- **Parent Phone (`NewChildPairingDialog`):** Added an informational card directly below the 6-digit code:
  > *"⏳ After entering this code on your child's phone, initial discovery & sync takes 10–15 seconds. Please keep both phones open."*
- **Child Phone (`ChildPairingScreen`):** During pairing handshake, an animated card with `CircularProgressIndicator` informs the user:
  > *"Connecting & Syncing Device... ⏳ — Verifying pairing code, registering device profile, and syncing initial app limits. Initial synchronization takes about 10–15 seconds. Please keep FamOrbit open."*

### B. Child Permissions Explainer Modal ("Why We Need These Permissions")
- **In `DeviceProtectionSetupCard`:** Added a prominent `ℹ️ Why are these permissions needed? (Tap to learn more)` button.
- **Explainer Modal Details:**
  1. **Usage Access:** Measures exact screen time and lets the parent see daily app usage.
  2. **Display Over Apps (Overlay):** Displays limit reminders and bedtime lock screens.
  3. **Accessibility Service:** Pauses restricted apps when time expires and prevents unapproved bypasses.
  4. **Why Parent Phone Doesn't Need Them:** Clearly explains that the parent phone is purely a remote controller reading cloud summaries, while the child phone is the enforced hardware requiring local system hooks.
  5. **Privacy Guarantee:** Clearly states that FamOrbit never reads private messages, passwords, photos, or keystrokes.

### C. Single Device Per Child Profile Enforcement (Approach B)
- **Design & Enforcement:** Strictly 1 active device is permitted per child profile to avoid ambiguous policies and dual telemetry conflicts.
- **Storage Protection (`ChildDeviceManager.kt`):** `addOrUpdateDevice(...)` calls `all.removeAll { it.childId == device.childId && it.deviceId != device.deviceId }` before saving, ensuring duplicate entries cannot persist.
- **UI Protection (`MainActivity.kt`):**
  - In `PairedDevicesCard`: If a device is already paired, the `+ Pair Device` button changes to `🔄 Replace Device`.
  - Tapping `🔄 Replace Device` opens an explicit warning modal:
    > *"Replace Paired Device? [Device Name] is already paired with [Child]'s profile. FamOrbit supports one active device per child profile. To pair a new device, the current device will be unpaired first. Continue?"*
  - Unpairs existing device and prompts the new pairing code dialog on confirmation.

### D. UI Decluttering & Simplification
- **Kept Intact:** 
  - `Category Budgets & Learn-First`: 5-category grouping and educational quota before entertainment.
  - Core app limits, daily screen time progress rings, routine schedules, remote instant freeze/unfreeze, and emergency SOS apps.
- **Removed from UI & Build:**
  - `Screen Time Piggy Bank (Rollover)`: Removed toggle and redemption cards from parent and child views.
  - `Gamified Badges & Digital Pledge`: Removed `🏆 Badges` & `📜 Digital Pledge` action buttons, dialogs, and cards.
  - `Weekly AI Executive Report Card`: Removed AI executive report card and toggle from parent screens.

---

## 5. Build & Deployment Specifications

- **Gradle Plugin / Kotlin:** Gradle 9.4.1 / AGP 8.9.0 / Kotlin 2.0.21
- **Target SDK / Min SDK:** `targetSdk = 36`, `minSdk = 26`
- **Output Artifacts:**
  - Master Release APK: `/Users/swapneshmhatre/Desktop/FamOrbit.apk` (2.4 MB, `versionCode=63`, `versionName=1.6.8` — Master Build)
  - Signed Play App Bundle: `/Users/swapneshmhatre/Desktop/FamOrbit.aab` / `app/build/outputs/bundle/release/app-release.aab` (5.2 MB, `versionCode=63`, R8 fullMode)
- **Testing Targets:**
  - Parent Phone: OnePlus 13R (`CPH2791` / `adb-3C15A70059B00000-PlSTYS._adb-tls-connect._tcp`)
  - Child Phone: OnePlus Nord CE4 Lite (`CPH2729` / `adb-dd990c56-hgu82G._adb-tls-connect._tcp`)
  - Virtual Devices: `emulator-5554` (Child phone portrait, API 36), `emulator-5556` (Parent tablet, API 36)
