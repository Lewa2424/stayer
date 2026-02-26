import CoreLocation

final class LocationService: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private let smoother = LocationSmoother(windowSize: 7)

    private var lastAcceptedRaw: CLLocation?
    private var lastSmoothed: CLLocation?

    var onDistanceDeltaKm: ((Double) -> Void)?

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = kCLDistanceFilterNone
        manager.pausesLocationUpdatesAutomatically = false
        manager.activityType = .fitness
        if #available(iOS 9.0, *) {
            manager.allowsBackgroundLocationUpdates = true
        }
    }

    func requestWhenInUse() {
        manager.requestWhenInUseAuthorization()
    }

    func requestAlways() {
        manager.requestAlwaysAuthorization()
    }

    func start() {
        manager.startUpdatingLocation()
    }

    func stop() {
        manager.stopUpdatingLocation()
        reset()
    }

    func reset() {
        lastAcceptedRaw = nil
        lastSmoothed = nil
        smoother.reset()
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        for location in locations {
            handle(location: location)
        }
    }

    private func handle(location: CLLocation) {
        guard let prev = lastAcceptedRaw else {
            lastAcceptedRaw = location
            lastSmoothed = smoother.addAndGetSmoothed(location)
            return
        }

        if !acceptPoint(prev: prev, cur: location) { return }

        lastAcceptedRaw = location
        let smoothed = smoother.addAndGetSmoothed(location)

        if let prevSmoothed = lastSmoothed {
            let deltaMeters = prevSmoothed.distance(from: smoothed)
            if deltaMeters > 0 {
                onDistanceDeltaKm?(deltaMeters / 1000.0)
            }
        }

        lastSmoothed = smoothed
    }

    private func acceptPoint(prev: CLLocation, cur: CLLocation) -> Bool {
        let dtSec = cur.timestamp.timeIntervalSince(prev.timestamp)
        if dtSec <= 0.2 { return false }

        let acc = cur.horizontalAccuracy
        if acc > 15.0 { return false }

        let d = prev.distance(from: cur)
        if d < 3.0 { return false }

        let v = d / dtSec
        if v < 0.5 { return false }
        if v > 7.5 { return false }

        if d > 120.0 && dtSec < 10.0 { return false }

        return true
    }
}

private final class LocationSmoother {
    private let windowSize: Int
    private var window: [CLLocation] = []

    init(windowSize: Int) {
        self.windowSize = windowSize
    }

    func reset() {
        window.removeAll()
    }

    func addAndGetSmoothed(_ location: CLLocation) -> CLLocation {
        if window.count == windowSize {
            window.removeFirst()
        }
        window.append(location)

        var latSum = 0.0
        var lonSum = 0.0
        for loc in window {
            latSum += loc.coordinate.latitude
            lonSum += loc.coordinate.longitude
        }
        let count = Double(window.count)
        let avgLat = latSum / count
        let avgLon = lonSum / count

        let smoothed = CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: avgLat, longitude: avgLon),
            altitude: location.altitude,
            horizontalAccuracy: location.horizontalAccuracy,
            verticalAccuracy: location.verticalAccuracy,
            timestamp: location.timestamp
        )
        return smoothed
    }
}
