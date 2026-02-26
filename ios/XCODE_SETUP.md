# Xcode Setup (iOS 12+)

## Project creation
1) Xcode -> New Project -> App (UIKit).
2) Product name: Stayer
3) Interface: UIKit, Language: Swift
4) Minimum iOS version: 12.0

## Add sources
- Drag `ios/StayeriOS/Sources` and `ios/StayeriOS/Resources` into the project.
- Ensure Localizable.strings and InfoPlist.strings are in Copy Bundle Resources.

## Info.plist
- Merge keys from `ios/Info.plist.template` into the project Info.plist.

## Capabilities
- Background Modes: Location updates
- If you keep background audio for TTS: Background Modes -> Audio

## Entitlements
- Add `ios/StayeriOS/Stayer.entitlements` to the target.

## Signing
- Use Apple Developer account for device install and TestFlight.

## Distribution
- TestFlight for early users.
- App Store for full release.
