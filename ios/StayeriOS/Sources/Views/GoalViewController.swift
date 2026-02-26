import UIKit

final class GoalViewController: UIViewController {
    private let store = WorkoutStore()

    private let distanceField = UITextField()
    private let timeField = UITextField()
    private let contentStack = UIStackView()

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = AppStyle.backgroundColor
        title = Strings.goalTitle

        setupUI()
        distanceField.text = store.loadTargetDistanceText()
        timeField.text = store.loadTargetTimeText()
    }

    private func setupUI() {
        let distanceLabel = UILabel()
        distanceLabel.text = Strings.goalDistanceLabel
        distanceLabel.font = UIFont.systemFont(ofSize: 15, weight: .semibold)
        distanceLabel.textColor = AppStyle.primaryTextColor

        distanceField.borderStyle = .roundedRect
        distanceField.placeholder = Strings.goalDistancePlaceholder
        distanceField.keyboardType = .decimalPad

        let timeLabel = UILabel()
        timeLabel.text = Strings.goalTimeLabel
        timeLabel.font = UIFont.systemFont(ofSize: 15, weight: .semibold)
        timeLabel.textColor = AppStyle.primaryTextColor

        timeField.borderStyle = .roundedRect
        timeField.placeholder = Strings.goalTimePlaceholder
        timeField.keyboardType = .numbersAndPunctuation

        let saveDistance = UIButton(type: .system)
        saveDistance.setTitle(Strings.goalSaveDistance, for: .normal)
        saveDistance.addTarget(self, action: #selector(saveDistanceTapped), for: .touchUpInside)
        saveDistance.titleLabel?.font = UIFont.systemFont(ofSize: 14, weight: .semibold)

        let saveTime = UIButton(type: .system)
        saveTime.setTitle(Strings.goalSaveTime, for: .normal)
        saveTime.addTarget(self, action: #selector(saveTimeTapped), for: .touchUpInside)
        saveTime.titleLabel?.font = UIFont.systemFont(ofSize: 14, weight: .semibold)

        let distanceCard = makeCard(title: distanceLabel, field: distanceField, button: saveDistance, badgeText: "D")
        let timeCard = makeCard(title: timeLabel, field: timeField, button: saveTime, badgeText: "T")

        contentStack.axis = .vertical
        contentStack.spacing = 16
        contentStack.translatesAutoresizingMaskIntoConstraints = false
        contentStack.addArrangedSubview(distanceCard)
        contentStack.addArrangedSubview(timeCard)

        view.addSubview(contentStack)
        NSLayoutConstraint.activate([
            contentStack.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 20),
            contentStack.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -20),
            contentStack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 20)
        ])
    }

    private func makeCard(title: UILabel, field: UITextField, button: UIButton, badgeText: String) -> UIView {
        let container = UIView()
        container.backgroundColor = AppStyle.secondaryBackgroundColor
        container.layer.cornerRadius = 16

        let badge = UILabel()
        badge.text = badgeText
        badge.textAlignment = .center
        badge.font = UIFont.systemFont(ofSize: 11, weight: .bold)
        badge.textColor = AppStyle.accentColor
        badge.backgroundColor = AppStyle.accentLightColor
        badge.layer.cornerRadius = 10
        badge.layer.masksToBounds = true
        badge.translatesAutoresizingMaskIntoConstraints = false

        let titleRow = UIStackView(arrangedSubviews: [badge, title])
        titleRow.axis = .horizontal
        titleRow.spacing = 6
        titleRow.alignment = .center

        let stack = UIStackView(arrangedSubviews: [titleRow, field, button])
        stack.axis = .vertical
        stack.spacing = 10
        stack.translatesAutoresizingMaskIntoConstraints = false

        container.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: container.leadingAnchor, constant: 16),
            stack.trailingAnchor.constraint(equalTo: container.trailingAnchor, constant: -16),
            stack.topAnchor.constraint(equalTo: container.topAnchor, constant: 16),
            stack.bottomAnchor.constraint(equalTo: container.bottomAnchor, constant: -16),
            badge.widthAnchor.constraint(equalToConstant: 20),
            badge.heightAnchor.constraint(equalToConstant: 20)
        ])
        return container
    }

    @objc private func saveDistanceTapped() {
        let text = distanceField.text ?? "0"
        store.saveTargetDistanceText(text)
        showToast(Strings.goalSavedDistance)
    }

    @objc private func saveTimeTapped() {
        let text = timeField.text ?? "0"
        store.saveTargetTimeText(text)
        showToast(Strings.goalSavedTime)
    }

    private func showToast(_ text: String) {
        let alert = UIAlertController(title: nil, message: text, preferredStyle: .alert)
        present(alert, animated: true)
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
            alert.dismiss(animated: true)
        }
    }
}
