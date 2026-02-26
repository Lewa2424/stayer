import UIKit

final class HistoryViewController: UITableViewController {
    private let store = WorkoutStore()
    private var history: [WorkoutHistory] = []

    override func viewDidLoad() {
        super.viewDidLoad()
        title = Strings.historyTitle
        tableView.register(HistoryCell.self, forCellReuseIdentifier: "cell")
        tableView.backgroundColor = AppStyle.backgroundColor
        tableView.separatorStyle = .none
        tableView.contentInset = UIEdgeInsets(top: 12, left: 0, bottom: 16, right: 0)
        loadData()
    }

    private func loadData() {
        history = store.loadHistory()
        tableView.reloadData()
    }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        return history.count
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "cell", for: indexPath)
        let item = history[indexPath.row]

        let paceText = formatPace(elapsedMs: item.elapsedMs, distanceKm: item.distanceKm)
        if let historyCell = cell as? HistoryCell {
            historyCell.configure(
                date: item.date,
                distance: String(format: "%.2f", item.distanceKm),
                time: item.timeText,
                pace: paceText
            )
        }
        return cell
    }

    override func tableView(_ tableView: UITableView, commit editingStyle: UITableViewCell.EditingStyle, forRowAt indexPath: IndexPath) {
        if editingStyle == .delete {
            history.remove(at: indexPath.row)
            store.saveHistory(history)
            tableView.deleteRows(at: [indexPath], with: .automatic)
        }
    }

    private func formatPace(elapsedMs: Int64, distanceKm: Double) -> String {
        if distanceKm <= 0 || elapsedMs <= 0 { return "--" }
        let totalSeconds = Double(elapsedMs) / 1000.0
        let secPerKm = Int(totalSeconds / distanceKm)
        let minutes = secPerKm / 60
        let seconds = secPerKm % 60
        return String(format: "%d:%02d", minutes, seconds)
    }
}

final class HistoryCell: UITableViewCell {
    private let card = UIView()
    private let dateLabel = UILabel()
    private let distanceLabel = UILabel()
    private let timeLabel = UILabel()
    private let paceLabel = UILabel()
    private let distanceBadge = UILabel()
    private let timeBadge = UILabel()

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        selectionStyle = .none
        backgroundColor = .clear

        card.backgroundColor = AppStyle.secondaryBackgroundColor
        card.layer.cornerRadius = 16
        card.translatesAutoresizingMaskIntoConstraints = false

        dateLabel.font = UIFont.systemFont(ofSize: 13, weight: .semibold)
        dateLabel.textColor = AppStyle.secondaryTextColor

        distanceLabel.font = UIFont.systemFont(ofSize: 18, weight: .semibold)
        timeLabel.font = UIFont.systemFont(ofSize: 18, weight: .semibold)
        paceLabel.font = UIFont.systemFont(ofSize: 14, weight: .medium)
        paceLabel.textColor = AppStyle.secondaryTextColor

        setupBadge(distanceBadge, text: "D")
        setupBadge(timeBadge, text: "T")

        let distanceStack = UIStackView(arrangedSubviews: [distanceBadge, distanceLabel])
        distanceStack.axis = .horizontal
        distanceStack.spacing = 6
        distanceStack.alignment = .center

        let timeStack = UIStackView(arrangedSubviews: [timeBadge, timeLabel])
        timeStack.axis = .horizontal
        timeStack.spacing = 6
        timeStack.alignment = .center

        let topRow = UIStackView(arrangedSubviews: [distanceStack, timeStack])
        topRow.axis = .horizontal
        topRow.distribution = .fillEqually
        topRow.spacing = 12

        let stack = UIStackView(arrangedSubviews: [dateLabel, topRow, paceLabel])
        stack.axis = .vertical
        stack.spacing = 8
        stack.translatesAutoresizingMaskIntoConstraints = false

        contentView.addSubview(card)
        card.addSubview(stack)

        NSLayoutConstraint.activate([
            card.leadingAnchor.constraint(equalTo: contentView.leadingAnchor, constant: 20),
            card.trailingAnchor.constraint(equalTo: contentView.trailingAnchor, constant: -20),
            card.topAnchor.constraint(equalTo: contentView.topAnchor, constant: 6),
            card.bottomAnchor.constraint(equalTo: contentView.bottomAnchor, constant: -6),

            stack.leadingAnchor.constraint(equalTo: card.leadingAnchor, constant: 16),
            stack.trailingAnchor.constraint(equalTo: card.trailingAnchor, constant: -16),
            stack.topAnchor.constraint(equalTo: card.topAnchor, constant: 12),
            stack.bottomAnchor.constraint(equalTo: card.bottomAnchor, constant: -12)
        ])
    }

    required init?(coder: NSCoder) {
        return nil
    }

    func configure(date: String, distance: String, time: String, pace: String) {
        dateLabel.text = date
        distanceLabel.text = "\(distance) km"
        timeLabel.text = time
        paceLabel.text = "\(Strings.historyPaceLabel): \(pace)"
    }

    private func setupBadge(_ badge: UILabel, text: String) {
        badge.text = text
        badge.textAlignment = .center
        badge.font = UIFont.systemFont(ofSize: 11, weight: .bold)
        badge.textColor = AppStyle.accentColor
        badge.backgroundColor = AppStyle.accentLightColor
        badge.layer.cornerRadius = 10
        badge.layer.masksToBounds = true
        badge.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            badge.widthAnchor.constraint(equalToConstant: 20),
            badge.heightAnchor.constraint(equalToConstant: 20)
        ])
    }
}
