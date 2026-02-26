import UIKit

final class InfoViewController: UIViewController {
    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = AppStyle.backgroundColor
        title = Strings.infoTitle

        let textView = UITextView()
        textView.isEditable = false
        textView.text = Strings.infoBody
        textView.font = UIFont.systemFont(ofSize: 16)
        textView.textColor = AppStyle.primaryTextColor
        textView.translatesAutoresizingMaskIntoConstraints = false
        textView.backgroundColor = .clear

        let setupButton = UIButton(type: .system)
        setupButton.setTitle(Strings.infoOpenSetup, for: .normal)
        setupButton.addTarget(self, action: #selector(openSetup), for: .touchUpInside)
        setupButton.translatesAutoresizingMaskIntoConstraints = false
        setupButton.titleLabel?.font = UIFont.systemFont(ofSize: 14, weight: .semibold)

        let infoCard = makeCard()

        let badge = UILabel()
        badge.text = "i"
        badge.textAlignment = .center
        badge.font = UIFont.systemFont(ofSize: 11, weight: .bold)
        badge.textColor = AppStyle.accentColor
        badge.backgroundColor = AppStyle.accentLightColor
        badge.layer.cornerRadius = 10
        badge.layer.masksToBounds = true
        badge.translatesAutoresizingMaskIntoConstraints = false

        infoCard.addSubview(textView)
        infoCard.addSubview(badge)

        let contacts = UILabel()
        contacts.numberOfLines = 0
        contacts.text = Strings.infoEmail + "\n" + Strings.infoCard
        contacts.font = UIFont.systemFont(ofSize: 14, weight: .regular)
        contacts.textColor = AppStyle.secondaryTextColor
        contacts.translatesAutoresizingMaskIntoConstraints = false

        let contactCard = makeCard()
        contactCard.addSubview(contacts)

        view.addSubview(infoCard)
        view.addSubview(contactCard)
        view.addSubview(setupButton)

        NSLayoutConstraint.activate([
            infoCard.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 20),
            infoCard.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -20),
            infoCard.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 16),

            badge.leadingAnchor.constraint(equalTo: infoCard.leadingAnchor, constant: 16),
            badge.topAnchor.constraint(equalTo: infoCard.topAnchor, constant: 12),
            badge.widthAnchor.constraint(equalToConstant: 20),
            badge.heightAnchor.constraint(equalToConstant: 20),

            textView.leadingAnchor.constraint(equalTo: infoCard.leadingAnchor, constant: 16),
            textView.trailingAnchor.constraint(equalTo: infoCard.trailingAnchor, constant: -16),
            textView.topAnchor.constraint(equalTo: infoCard.topAnchor, constant: 12),
            textView.bottomAnchor.constraint(equalTo: infoCard.bottomAnchor, constant: -12),

            contactCard.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 20),
            contactCard.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -20),
            contactCard.topAnchor.constraint(equalTo: infoCard.bottomAnchor, constant: 16),

            contacts.leadingAnchor.constraint(equalTo: contactCard.leadingAnchor, constant: 16),
            contacts.trailingAnchor.constraint(equalTo: contactCard.trailingAnchor, constant: -16),
            contacts.topAnchor.constraint(equalTo: contactCard.topAnchor, constant: 12),
            contacts.bottomAnchor.constraint(equalTo: contactCard.bottomAnchor, constant: -12),

            setupButton.topAnchor.constraint(equalTo: contactCard.bottomAnchor, constant: 20),
            setupButton.centerXAnchor.constraint(equalTo: view.centerXAnchor)
        ])
    }

    @objc private func openSetup() {
        let vc = SetupChecklistViewController()
        navigationController?.pushViewController(vc, animated: true)
    }

    private func makeCard() -> UIView {
        let container = UIView()
        container.backgroundColor = AppStyle.secondaryBackgroundColor
        container.layer.cornerRadius = 16
        container.translatesAutoresizingMaskIntoConstraints = false
        return container
    }
}
