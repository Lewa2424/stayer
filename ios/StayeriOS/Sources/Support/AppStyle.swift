import UIKit

enum AppStyle {
    static var backgroundColor: UIColor {
        if #available(iOS 13.0, *) { return UIColor.systemBackground }
        return UIColor.white
    }

    static var secondaryBackgroundColor: UIColor {
        if #available(iOS 13.0, *) { return UIColor.secondarySystemBackground }
        return UIColor(white: 0.95, alpha: 1.0)
    }

    static var primaryTextColor: UIColor {
        if #available(iOS 13.0, *) { return UIColor.label }
        return UIColor.black
    }

    static var secondaryTextColor: UIColor {
        if #available(iOS 13.0, *) { return UIColor.secondaryLabel }
        return UIColor.darkGray
    }

    static var accentColor: UIColor {
        return UIColor.orange
    }

    static var accentLightColor: UIColor {
        return UIColor.orange.withAlphaComponent(0.2)
    }
}
