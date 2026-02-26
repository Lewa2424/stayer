import UIKit
import CoreLocation

final class MainViewController: UIViewController {
    private let engine = WorkoutEngine()
    private let store = WorkoutStore()

    private let bigButton = UIButton(type: .system)
    private let hintLabel = UILabel()
    private let timerLabel = UILabel()
    private let distanceLabel = UILabel()
    private let paceLabel = UILabel()
    private let goalLabel = UILabel()
    private let targetTimeLabel = UILabel()

    private let statsStack = UIStackView()
    private let bottomPanel = UIView()
    private let topSpacer = UIView()
    private let gradientLayer = CAGradientLayer()

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = AppStyle.backgroundColor
        title = Strings.appName

        navigationItem.rightBarButtonItem = UIBarButtonItem(
            title: Strings.mainHistory,
            style: .plain,
            target: self,
            action: #selector(openHistory)
        )
        navigationItem.leftBarButtonItem = UIBarButtonItem(
            title: Strings.mainInfo,
            style: .plain,
            target: self,
            action: #selector(openInfo)
        )

        setupUI()
        bindEngine()

        NotificationCenter.default.addObserver(
            self,
            selector: #selector(handleAppForeground),
            name: UIApplication.willEnterForegroundNotification,
            object: nil
        )
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        gradientLayer.frame = view.bounds
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        if !store.isSetupDone() {
            let vc = SetupChecklistViewController()
            navigationController?.pushViewController(vc, animated: true)
        }
    }

    private func bindEngine() {
        engine.onUpdate = { [weak self] in
            self?.refreshUI()
        }
        refreshUI()
    }

    private func setupUI() {
        gradientLayer.colors = [
            AppStyle.backgroundColor.cgColor,
            AppStyle.secondaryBackgroundColor.cgColor
        ]
        gradientLayer.startPoint = CGPoint(x: 0.5, y: 0.0)
        gradientLayer.endPoint = CGPoint(x: 0.5, y: 1.0)
        view.layer.insertSublayer(gradientLayer, at: 0)

        bigButton.translatesAutoresizingMaskIntoConstraints = false
        bigButton.setTitle(Strings.mainStart, for: .normal)
        bigButton.titleLabel?.font = UIFont.systemFont(ofSize: 30, weight: .bold)
        bigButton.backgroundColor = AppStyle.accentLightColor
        bigButton.layer.cornerRadius = 110
        bigButton.layer.borderWidth = 2
        bigButton.layer.borderColor = AppStyle.accentColor.cgColor
        bigButton.addTarget(self, action: #selector(primaryAction), for: .touchUpInside)

        let longPress = UILongPressGestureRecognizer(target: self, action: #selector(longPressAction(_:)))
        longPress.minimumPressDuration = 1.0
        bigButton.addGestureRecognizer(longPress)

        hintLabel.translatesAutoresizingMaskIntoConstraints = false
        hintLabel.text = Strings.mainHoldHint
        hintLabel.textColor = AppStyle.secondaryTextColor
        hintLabel.textAlignment = .center
        hintLabel.font = UIFont.systemFont(ofSize: 14)

        timerLabel.font = UIFont.monospacedDigitSystemFont(ofSize: 22, weight: .semibold)
        distanceLabel.font = UIFont.systemFont(ofSize: 20, weight: .semibold)
        paceLabel.font = UIFont.systemFont(ofSize: 20, weight: .semibold)
        goalLabel.font = UIFont.systemFont(ofSize: 20, weight: .semibold)
        targetTimeLabel.font = UIFont.systemFont(ofSize: 16, weight: .regular)
        targetTimeLabel.textColor = AppStyle.secondaryTextColor

        bottomPanel.translatesAutoresizingMaskIntoConstraints = false
        bottomPanel.backgroundColor = AppStyle.secondaryBackgroundColor
        bottomPanel.layer.cornerRadius = 24
        bottomPanel.layer.maskedCorners = [.layerMinXMinYCorner, .layerMaxXMinYCorner]
        bottomPanel.layer.shadowColor = UIColor.black.cgColor
        bottomPanel.layer.shadowOpacity = 0.08
        bottomPanel.layer.shadowRadius = 10
        bottomPanel.layer.shadowOffset = CGSize(width: 0, height: -2)

        statsStack.axis = .vertical
        statsStack.spacing = 12
        statsStack.translatesAutoresizingMaskIntoConstraints = false

        let row1 = UIStackView(arrangedSubviews: [
            statBlock(title: Strings.mainTime, valueLabel: timerLabel, iconText: "T"),
            statBlock(title: Strings.mainDistance, valueLabel: distanceLabel, iconText: "D")
        ])
        row1.axis = .horizontal
        row1.distribution = .fillEqually
        row1.spacing = 16

        let row2 = UIStackView(arrangedSubviews: [
            statBlock(title: Strings.mainPace, valueLabel: paceLabel, iconText: "P"),
            goalBlock()
        ])
        row2.axis = .horizontal
        row2.distribution = .fillEqually
        row2.spacing = 16

        statsStack.addArrangedSubview(row1)
        statsStack.addArrangedSubview(row2)

        view.addSubview(bigButton)
        view.addSubview(hintLabel)
        view.addSubview(bottomPanel)
        bottomPanel.addSubview(statsStack)

        NSLayoutConstraint.activate([
            bigButton.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            bigButton.centerYAnchor.constraint(equalTo: view.centerYAnchor, constant: -30),
            bigButton.widthAnchor.constraint(equalToConstant: 220),
            bigButton.heightAnchor.constraint(equalToConstant: 220),

            hintLabel.topAnchor.constraint(equalTo: bigButton.bottomAnchor, constant: 12),
            hintLabel.centerXAnchor.constraint(equalTo: view.centerXAnchor),

            bottomPanel.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            bottomPanel.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            bottomPanel.bottomAnchor.constraint(equalTo: view.safeAreaLayoutGuide.bottomAnchor),

            statsStack.leadingAnchor.constraint(equalTo: bottomPanel.leadingAnchor, constant: 24),
            statsStack.trailingAnchor.constraint(equalTo: bottomPanel.trailingAnchor, constant: -24),
            statsStack.topAnchor.constraint(equalTo: bottomPanel.topAnchor, constant: 20),
            statsStack.bottomAnchor.constraint(equalTo: bottomPanel.bottomAnchor, constant: -24)
        ])
    }

    private func statBlock(title: String, valueLabel: UILabel, iconText: String) -> UIView {
        let container = UIView()
        container.backgroundColor = AppStyle.backgroundColor
        container.layer.cornerRadius = 12

        let badge = UILabel()
        badge.text = iconText
        badge.textAlignment = .center
        badge.font = UIFont.systemFont(ofSize: 11, weight: .bold)
        badge.textColor = AppStyle.accentColor
        badge.backgroundColor = AppStyle.accentLightColor
        badge.layer.cornerRadius = 10
        badge.layer.masksToBounds = true
        badge.translatesAutoresizingMaskIntoConstraints = false

        let titleLabel = UILabel()
        titleLabel.text = title
        titleLabel.font = UIFont.systemFont(ofSize: 12, weight: .medium)
        titleLabel.textColor = AppStyle.secondaryTextColor

        valueLabel.textColor = AppStyle.primaryTextColor
        let titleRow = UIStackView(arrangedSubviews: [badge, titleLabel])
        titleRow.axis = .horizontal
        titleRow.spacing = 6
        titleRow.alignment = .center

        let stack = UIStackView(arrangedSubviews: [valueLabel, titleRow])
        stack.axis = .vertical
        stack.spacing = 4
        stack.translatesAutoresizingMaskIntoConstraints = false

        container.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: container.leadingAnchor, constant: 12),
            stack.trailingAnchor.constraint(equalTo: container.trailingAnchor, constant: -12),
            stack.topAnchor.constraint(equalTo: container.topAnchor, constant: 12),
            stack.bottomAnchor.constraint(equalTo: container.bottomAnchor, constant: -12),
            badge.widthAnchor.constraint(equalToConstant: 20),
            badge.heightAnchor.constraint(equalToConstant: 20)
        ])
        return container
    }

    private func goalBlock() -> UIView {
        let container = UIView()
        container.backgroundColor = AppStyle.backgroundColor
        container.layer.cornerRadius = 12

        let chevron = UILabel()
        chevron.text = ">"
        chevron.font = UIFont.systemFont(ofSize: 16, weight: .semibold)
        chevron.textColor = AppStyle.secondaryTextColor

        let topRow = UIStackView(arrangedSubviews: [goalLabel, chevron])
        topRow.axis = .horizontal
        topRow.distribution = .equalSpacing

        let actionRow = UIStackView()
        actionRow.axis = .horizontal
        actionRow.spacing = 6

        let goalTitle = UILabel()
        goalTitle.text = Strings.mainGoal
        goalTitle.font = UIFont.systemFont(ofSize: 12, weight: .medium)
        goalTitle.textColor = AppStyle.secondaryTextColor

        let actionLabel = UILabel()
        actionLabel.text = Strings.mainSetGoal
        actionLabel.font = UIFont.systemFont(ofSize: 12, weight: .semibold)
        actionLabel.textColor = AppStyle.accentColor

        actionRow.addArrangedSubview(goalTitle)
        actionRow.addArrangedSubview(actionLabel)

        let stack = UIStackView(arrangedSubviews: [topRow, targetTimeLabel, actionRow])
        stack.axis = .vertical
        stack.spacing = 6
        stack.translatesAutoresizingMaskIntoConstraints = false

        container.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: container.leadingAnchor, constant: 12),
            stack.trailingAnchor.constraint(equalTo: container.trailingAnchor, constant: -12),
            stack.topAnchor.constraint(equalTo: container.topAnchor, constant: 12),
            stack.bottomAnchor.constraint(equalTo: container.bottomAnchor, constant: -12)
        ])
        let tap = UITapGestureRecognizer(target: self, action: #selector(openGoal))
        container.addGestureRecognizer(tap)
        container.isUserInteractionEnabled = true
        return container
    }

    private func refreshUI() {
        timerLabel.text = engine.currentTimerText()
        distanceLabel.text = String(format: "%.2f km", engine.currentDistanceKm())
        paceLabel.text = engine.currentPaceText()

        let targetDistance = engine.targetDistanceText
        let targetTime = engine.targetTimeText
        let targetValue = Double(targetDistance.replacingOccurrences(of: ",", with: ".")) ?? 0.0
        goalLabel.text = targetValue > 0 ? String(format: "%.2f km", targetValue) : "--"
        targetTimeLabel.text = targetTime

        let running = engine.isRunning && !engine.isPaused
        bigButton.setTitle(running ? Strings.mainPause : Strings.mainStart, for: .normal)
        bigButton.backgroundColor = running ? AppStyle.accentColor : AppStyle.accentLightColor
    }

    @objc private func primaryAction() {
        if engine.isRunning && !engine.isPaused {
            engine.pause()
            return
        }

        if !isLocationAuthorizedForUse() {
            engine.requestLocationWhenInUse()
            showLocationAlert()
            return
        }

        engine.startOrResume()
        requestAlwaysIfNeeded()
    }

    @objc private func longPressAction(_ gr: UILongPressGestureRecognizer) {
        if gr.state == .began {
            engine.stopAndReset()
        }
    }

    @objc private func openGoal() {
        let vc = GoalViewController()
        navigationController?.pushViewController(vc, animated: true)
    }

    @objc private func openHistory() {
        let vc = HistoryViewController()
        navigationController?.pushViewController(vc, animated: true)
    }

    @objc private func openInfo() {
        let vc = InfoViewController()
        navigationController?.pushViewController(vc, animated: true)
    }

    @objc private func handleAppForeground() {
        refreshUI()
    }

    private func isLocationAuthorizedForUse() -> Bool {
        let status = CLLocationManager.authorizationStatus()
        return status == .authorizedAlways || status == .authorizedWhenInUse
    }

    private func showLocationAlert() {
        let alert = UIAlertController(title: Strings.alertLocationTitle, message: Strings.alertLocationMessage, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: Strings.alertOk, style: .default))
        present(alert, animated: true)
    }

    private func requestAlwaysIfNeeded() {
        let status = CLLocationManager.authorizationStatus()
        guard status == .authorizedWhenInUse else { return }

        let alert = UIAlertController(
            title: Strings.alertLocationUpgradeTitle,
            message: Strings.alertLocationUpgradeMessage,
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: Strings.alertOk, style: .cancel))
        alert.addAction(UIAlertAction(title: Strings.alertAllow, style: .default) { [weak self] _ in
            self?.engine.requestLocationAlways()
        })
        present(alert, animated: true)
    }
}
