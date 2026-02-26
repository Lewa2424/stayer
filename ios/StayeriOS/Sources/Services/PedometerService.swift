import CoreMotion

final class PedometerService {
    private let pedometer = CMPedometer()
    private var startDate: Date?

    var onDistanceKm: ((Double) -> Void)?

    func start() {
        guard CMPedometer.isDistanceAvailable() else { return }
        let start = Date()
        startDate = start
        pedometer.startUpdates(from: start) { [weak self] data, _ in
            guard let self = self else { return }
            guard let meters = data?.distance?.doubleValue else { return }
            self.onDistanceKm?(meters / 1000.0)
        }
    }

    func stop() {
        pedometer.stopUpdates()
        startDate = nil
    }
}
