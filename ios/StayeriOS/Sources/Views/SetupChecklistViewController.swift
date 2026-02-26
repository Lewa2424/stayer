import UIKit
import CoreLocation
import UserNotifications
import CoreMotion

final class SetupChecklistViewController: UIViewController {
    private let locationManager = CLLocationManager()
    private let pedometer = CMPedometer()
    private let store = WorkoutStore()

    private let locationStatusLabel = UILabel()
    private let notificationStatusLabel = UILabel()
    private let motionStatusLabel = UILabel()
    private let doneButton = UIButton(type: .system)
    private var actionBoxes: [ActionBox] = []

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = AppStyle.backgroundColor
        title = Strings.setupTitle
        locationManager.delegate = self
        setupUI()
        refreshStatuses()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        refreshStatuses()
    }

    private func setupUI() {
        let locationRow = makeRow(
            title: Strings.setupLocationAlways,
            statusLabel: locationStatusLabel,
            actionTitle: Strings.setupRequest,
            badgeText: "L"
        ) { [weak self] in
            self?.requestLocationPermission()
        }

        let notificationRow = makeRow(
            title: Strings.setupNotifications,
            statusLabel: notificationStatusLabel,
            actionTitle: Strings.setupRequest,
            badgeText: "N"
        ) { [weak self] in
            UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in
                DispatchQueue.main.async { self?.refreshStatuses() }
            }
        }

        let motionRow = makeRow(
            title: Strings.setupMotion,
            statusLabel: motionStatusLabel,
            actionTitle: Strings.setupRequest,
            badgeText: "M"
        ) { [weak self] in
            self?.requestMotionAccess()
        }

        let locationCard = wrapCard(locationRow)
        let notificationCard = wrapCard(notificationRow)
        let motionCard = wrapCard(motionRow)

        let openSettings = UIButton(type: .system)
        openSettings.setTitle(Strings.setupOpenSettings, for: .normal)
        openSettings.addTarget(self, action: #selector(openAppSettings), for: .touchUpInside)
        openSettings.titleLabel?.font = UIFont.systemFont(ofSize: 14, weight: .semibold)

        doneButton.setTitle(Strings.setupDone, for: .normal)
        doneButton.addTarget(self, action: #selector(markDone), for: .touchUpInside)
        doneButton.isEnabled = false

        let stack = UIStackView(arrangedSubviews: [locationCard, notificationCard, motionCard, openSettings, doneButton])
        stack.axis = .vertical
        stack.spacing = 16
        stack.translatesAutoresizingMaskIntoConstraints = false

        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 16),
            stack.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -16),
            stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 20)
        ])
    }

    private func makeRow(title: String, statusLabel: UILabel, actionTitle: String, badgeText: String, action: @escaping () -> Void) -> UIView {
        let titleLabel = UILabel()
        titleLabel.text = title
        titleLabel.font = UIFont.systemFont(ofSize: 16, weight: .medium)

        statusLabel.text = Strings.setupStatusNotRequested
        statusLabel.textColor = AppStyle.secondaryTextColor

        let badge = UILabel()
        badge.text = badgeText
        badge.textAlignment = .center
        badge.font = UIFont.systemFont(ofSize: 11, weight: .bold)
        badge.textColor = AppStyle.accentColor
        badge.backgroundColor = AppStyle.accentLightColor
        badge.layer.cornerRadius = 10
        badge.layer.masksToBounds = true
        badge.translatesAutoresizingMaskIntoConstraints = false

        let button = UIButton(type: .system)
        button.setTitle(actionTitle, for: .normal)
        button.titleLabel?.font = UIFont.systemFont(ofSize: 14, weight: .semibold)
        let box = ActionBox(action)
        actionBoxes.append(box)
        button.addTarget(box, action: #selector(ActionBox.invoke), for: .touchUpInside)

        let titleRow = UIStackView(arrangedSubviews: [badge, titleLabel])
        titleRow.axis = .horizontal
        titleRow.spacing = 6
        titleRow.alignment = .center

        let row = UIStackView(arrangedSubviews: [titleRow, statusLabel, button])
        row.axis = .horizontal
        row.alignment = .center
        row.distribution = .fillProportionally
        row.spacing = 12
        NSLayoutConstraint.activate([
            badge.widthAnchor.constraint(equalToConstant: 20),
            badge.heightAnchor.constraint(equalToConstant: 20)
        ])
        return row
    }

    private func wrapCard(_ content: UIView) -> UIView {
        let card = UIView()
        card.backgroundColor = AppStyle.secondaryBackgroundColor
        card.layer.cornerRadius = 16
        card.translatesAutoresizingMaskIntoConstraints = false

        content.translatesAutoresizingMaskIntoConstraints = false
        card.addSubview(content)
        NSLayoutConstraint.activate([
            content.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 16),
            content.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -16),
            content.topAnchor.constraint(equalTo: card.topAnchor, constant: 12),
            content.bottomAnchor.constraint(equalTo: card.bottomAnchor, constant: -12)
        ])

        return card
    }

    private func refreshStatuses() {
        let auth = CLLocationManager.authorizationStatus()
        let locationAuthorizedAlways = (auth == .authorizedAlways)
        switch auth {
        case .authorizedAlways: locationStatusLabel.text = Strings.setupStatusGranted
        case .authorizedWhenInUse: locationStatusLabel.text = Strings.setupStatusWhenInUse
        case .denied: locationStatusLabel.text = Strings.setupStatusDenied
        case .restricted: locationStatusLabel.text = Strings.setupStatusRestricted
        case .notDetermined: locationStatusLabel.text = Strings.setupStatusNotRequested
        @unknown default: locationStatusLabel.text = Strings.setupStatusNotRequested
        }

        UNUserNotificationCenter.current().getNotificationSettings { [weak self] settings in
            DispatchQueue.main.async {
                let notificationsOk = (settings.authorizationStatus == .authorized)
                self?.notificationStatusLabel.text = notificationsOk ? Strings.setupStatusGranted : Strings.setupStatusNotRequested
                self?.doneButton.isEnabled = locationAuthorizedAlways && notificationsOk
            }
        }

        let motionStatus = CMPedometer.authorizationStatus()
        switch motionStatus {
        case .authorized: motionStatusLabel.text = Strings.setupStatusGranted
        case .denied: motionStatusLabel.text = Strings.setupStatusDenied
        case .restricted: motionStatusLabel.text = Strings.setupStatusRestricted
        case .notDetermined: motionStatusLabel.text = Strings.setupStatusNotRequested
        @unknown default: motionStatusLabel.text = Strings.setupStatusNotRequested
        }
    }

    private func requestMotionAccess() {
        guard CMPedometer.isDistanceAvailable() else { return }
        pedometer.queryPedometerData(from: Date(), to: Date()) { [weak self] _, _ in
            DispatchQueue.main.async { self?.refreshStatuses() }
        }
    }

    private func requestLocationPermission() {
        let status = CLLocationManager.authorizationStatus()
        if status == .notDetermined {
            locationManager.requestWhenInUseAuthorization()
            return
        }
        if status == .authorizedWhenInUse {
            locationManager.requestAlwaysAuthorization()
            return
        }
        locationManager.requestAlwaysAuthorization()
    }

    @objc private func openAppSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url, options: [:], completionHandler: nil)
    }

    @objc private func markDone() {
        store.markSetupDone()
        navigationController?.popViewController(animated: true)
    }
}

private final class ActionBox {
    let action: () -> Void
    init(_ action: @escaping () -> Void) {
        self.action = action
    }

    @objc func invoke() {
        action()
    }
}

extension SetupChecklistViewController: CLLocationManagerDelegate {
    @available(iOS 14.0, *)
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        refreshStatuses()
    }

    func locationManager(_ manager: CLLocationManager, didChangeAuthorization status: CLAuthorizationStatus) {
        refreshStatuses()
    }
}
