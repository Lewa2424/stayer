import UIKit

@UIApplicationMain
class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        if #available(iOS 13.0, *) {
            // SceneDelegate will handle window.
        } else {
            let window = UIWindow(frame: UIScreen.main.bounds)
            let root = UINavigationController(rootViewController: MainViewController())
            window.rootViewController = root
            window.makeKeyAndVisible()
            self.window = window
        }
        return true
    }
}
