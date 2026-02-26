# Инструкции по сборке iOS

Этот проект содержит исходники iOS‑версии (UIKit, iOS 12+). Ниже — краткая инструкция, как собрать и установить приложение на iPhone через Xcode.

## Что нужно

- Mac с установленным Xcode
- Apple Developer аккаунт (для подписи и установки на устройство)

## Быстрый старт

1) Открой Xcode и создай новый проект: File -> New -> Project -> App (UIKit).
2) Название: Stayer, Language: Swift, Interface: UIKit.
3) Deployment Target: iOS 12.0.
4) Перетащи в проект папки:
   - `ios/StayeriOS/Sources`
   - `ios/StayeriOS/Resources`
5) Убедись, что `Localizable.strings` и `InfoPlist.strings` попали в Copy Bundle Resources.
6) Добавь ключи из `ios/Info.plist.template` в Info.plist проекта.
7) Включи Capabilities:
   - Background Modes -> Location updates
   - (Опционально) Background Modes -> Audio (если нужен TTS в фоне)
8) Добавь entitlements файл: `ios/StayeriOS/Stayer.entitlements`.
9) Настрой Signing (Apple ID/Team).
10) Собери и запусти на устройстве.

## Установка пользователям

- Для теста: TestFlight (нужен Apple Developer аккаунт).
- Для релиза: App Store.

## Важно

- Без Mac и Xcode собрать IPA нельзя.
- Без Apple Developer аккаунта установить на iPhone тоже нельзя.

## Дополнительно

Подробный чеклист: `ios/XCODE_SETUP.md`.
