# Stayer iOS Port (Preparation)

This folder contains a Swift/UIKit scaffold for an iOS 12+ port.
It is not a buildable Xcode project yet. Use it as a drop-in source
set when creating a new iOS app in Xcode.

## Goals
- Preserve existing behavior and UX from the Android app
- Use native iOS frameworks for location, TTS, notifications, and motion
- Keep permission requests compliant with Apple review requirements

## What is included
- UIKit views mirroring the Android Compose structure
- Services for location, TTS, notifications, pedometer
- Core workout engine logic (timing, distance, pace prompts, auto-pause)
- Storage layer for goals and history (UserDefaults + Codable)
- Info.plist template with required usage strings and background modes

## What is NOT included
- Xcode project file (.xcodeproj)
- Signing / provisioning / App Store assets
- Final UI polish (colors/fonts/icons)

## Next steps in Xcode
1) Create a new iOS App (UIKit) named "Stayer".
2) Drag all files from `ios/StayeriOS/Sources` into the Xcode project.
3) Drag `ios/StayeriOS/Resources` into the Xcode project (copy items if needed).
4) Add `Info.plist` keys from `ios/Info.plist.template` to your Info.plist.
5) Ensure `InfoPlist.strings` in `Resources/en.lproj` and `Resources/ru.lproj` are included.
6) Enable Capabilities:
   - Background Modes: Location updates
   - (Optional) Background Audio if you decide to keep TTS in background
7) Add the entitlements file: `ios/StayeriOS/Stayer.entitlements`.
8) Set bundle id and signing (for device install and TestFlight).
9) Replace placeholder UI strings with localized content.
10) Set deployment target to iOS 12.0.

See `ios/XCODE_SETUP.md` for a full step-by-step checklist.

## Installability notes
- Users can install via TestFlight or App Store distribution.
- For local device installs you must sign with a valid Apple Developer account.
- For broad installability, TestFlight is the fastest distribution path.

## Permissions and Apple review
- Request Location (When In Use) first, then upgrade to Always after a
  clear user action (start workout).
- Request Notifications only when user enables pace prompts.
- Request Motion access only when step tracking is enabled.
- Do not request anything on first launch without a user action.

## Distribution options
- TestFlight: easiest for early users, requires Apple Developer account.
- App Store: full review; ensure permission prompts match actual usage.
- Ad Hoc: limited devices via UDID (not recommended for scale).

## Mapping (Android -> iOS)
- ForegroundService -> Background Location Mode + CLLocationManager
- TTS (TextToSpeech) -> AVSpeechSynthesizer
- SharedPreferences -> UserDefaults + Codable
- Notifications -> UNUserNotificationCenter (local)

## Known iOS constraints
- iOS may throttle background location updates.
- Background TTS may require audio background mode and is not guaranteed.
- Strict permission rationale is required for App Store review.
