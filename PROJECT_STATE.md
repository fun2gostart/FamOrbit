# FamOrbit (FamilyControl) — Project State & Documentation

**Project Name:** FamOrbit (FamilyControl)  
**Package:** `com.familycontrol.lab`  
**Current Version:** `1.5.0` (`versionCode 45`)  
**Workspace Directory:** `/Users/swapneshmhatre/Desktop/Fam orbrit time machine/FamOrbit/FamilyControl_v1.3.0_ExtraTimeEnforcement`  
**Last Updated:** October 7, 2026  

---

## 1. Overview & Architecture

FamOrbit is a dual-role (Parent / Child) digital wellbeing and parental control Android application built using Jetpack Compose, Kotlin coroutines, Android Accessibility Service, and a Render-hosted Python/FastAPI cloud backend.

### Key Roles
- **Parent Device (`ROLE_PARENT`):** Remote dashboard and control center. Publishes screen time policies, sets app limits, configures focus presets (Focus, Bedtime, Dinner), grants extra time requests, and reviews synced telemetry. Does **not** require device-level accessibility or overlay permissions.
- **Child Device (`ROLE_CHILD`):** Enforced hardware target. Measures app usage via Android `UsageStatsManager`, enforces limits via `AccessibilityService` (`AccessibilityGuardEngine`), displays lock/timeout screens via System Alert Overlays, and synchronizes real-time telemetry with the parent phone through cloud endpoints.

---

## 2. Cloud Backend & Pairing Architecture

- **Backend Base URL:** `https://familycontrol-render.onrender.com`
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

- **Gradle Plugin / Kotlin:** Gradle 8.13 / AGP 8.9.1 / Kotlin 2.0.21
- **Target SDK / Min SDK:** `targetSdk = 35`, `minSdk = 26`
- **Output Artifact:** `app/build/outputs/apk/release/app-release.apk`
- **Testing Targets:**
  - Parent Phone: OnePlus 13R (`CPH2791`)
  - Child Phone: OnePlus Nord CE4 Lite (`CPH2729`)
  - Virtual Device: `emulator-5554` (ARM64 API 35)
