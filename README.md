# FamilyControl v3.0.0 — Enterprise Parental Control & Security Suite

## Overview
FamilyControl is an advanced Android Parental Control platform featuring Device Owner enforcement, offline-safe screen time monitoring, Parent PIN protection, instant remote lock, system anti-tamper guard, web content filtering, and screen time analytics.

---

## What's New in v3.0.0

### 1. ⚡ Instant Remote Lock ("Pause Device")
- Parent can instantly pause all managed apps on demand from Parent Control Center.
- Overrides screen time allowances and locks apps immediately via `EnforcementService`.

### 2. 🛡️ System Guard & Installation Protection (`SystemGuardEngine.kt`)
- Blocks APK sideloading (`DISALLOW_INSTALL_UNKNOWN_SOURCES`).
- Blocks changing device date/time to bypass limits (`DISALLOW_CONFIG_DATE_TIME`).
- Blocks safe-mode bypass (`DISALLOW_SAFE_BOOT`) and app uninstallation (`DISALLOW_UNINSTALL_APPS`).

### 3. 🌐 Web & SafeSearch Content Filter (`WebFilterEngine.kt`)
- Forces Google SafeSearch and YouTube Restricted Mode via Chrome Managed Configurations.
- Enforces URL domain blocklists for adult/gambling categories.

### 4. 📊 Screen Time Analytics & Category Insights (`AnalyticsEngine.kt`)
- Categorizes app usage into *Social*, *Entertainment*, *Gaming*, *Education*, and *Productivity*.
- Generates usage trend reports and triggers anomaly alerts for excessive gaming or total screen time spikes.

### 5. 🔒 Parent PIN Security & Notifications
- 4-digit Parent Security PIN (`1234` default) for Parent Mode and Control Center.
- Real-time heads-up alerts on high-priority channel (`familycontrol_alerts`).

---

## Test & Handoff Guide

1. **Backend Verification**:
   - Docker container running at `http://127.0.0.1:8001`.
2. **Launch App**:
   - Install APK: `adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk`
   - Start app: `adb -s emulator-5554 shell am start -n com.familycontrol.lab/.MainActivity`
3. **Instant Lock Test**:
   - Unlock Parent Control Center (PIN `1234`) and toggle **INSTANT REMOTE LOCK**.
   - Verify managed apps suspend immediately.
4. **Protection Health Test**:
   - Open **Protection Health** and toggle **System Anti-Tamper Guard** and **Web Content Filter**.
