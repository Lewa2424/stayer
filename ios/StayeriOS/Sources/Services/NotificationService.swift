import UserNotifications

final class NotificationService {
    private let center = UNUserNotificationCenter.current()

    func requestAuthorization(completion: @escaping (Bool) -> Void) {
        center.requestAuthorization(options: [.alert, .sound]) { granted, _ in
            completion(granted)
        }
    }

    func isEnabled(completion: @escaping (Bool) -> Void) {
        center.getNotificationSettings { settings in
            completion(settings.authorizationStatus == .authorized)
        }
    }

    func notify(title: String, body: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = UNNotificationSound.default

        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: 1, repeats: false)
        let request = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: trigger)
        center.add(request, withCompletionHandler: nil)
    }
}
