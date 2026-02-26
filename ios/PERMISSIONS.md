# iOS Permissions Rationale

This file documents why each permission is required.
Use the same language in the app UI and Info.plist.

- Location (When In Use): Needed to track workout distance in real time.
- Location (Always): Needed to track workouts when the screen is off.
- Motion: Needed to estimate step distance when GPS is weak.
- Notifications: Needed to deliver pace and workout updates.

Request timing (Apple review friendly):
- Do not request on first launch.
- Ask When In Use when user taps Start.
- Ask Always only after user starts a workout and understands why.
- Ask Notifications when enabling pace prompts.
- Ask Motion when enabling step tracking.
