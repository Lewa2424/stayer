import Foundation
import UIKit

final class WorkoutEngine {
    private let store = WorkoutStore()
    private let runtimeStore = WorkoutRuntimeStore()
    private let locationService = LocationService()
    private let pedometerService = PedometerService()
    private let ttsService = TTSService()
    private let notificationService = NotificationService()

    private var timer: Timer?

    private var gpsDistanceKm: Double = 0.0
    private var stepDistanceKm: Double = 0.0
    private var lastPaceCheckDistance: Double = 0.0
    private var goalReached = false

    private var startTimeMs: Int64 = 0
    private var pausedAtMs: Int64 = 0
    private var totalPausedMs: Int64 = 0

    private(set) var isRunning: Bool = false
    private(set) var isPaused: Bool = false

    var targetDistanceText: String {
        store.loadTargetDistanceText()
    }

    var targetTimeText: String {
        store.loadTargetTimeText()
    }

    var onUpdate: (() -> Void)?

    init() {
        locationService.onDistanceDeltaKm = { [weak self] deltaKm in
            self?.gpsDistanceKm += deltaKm
            self?.handleDistanceUpdate()
        }
        pedometerService.onDistanceKm = { [weak self] km in
            self?.stepDistanceKm = km
            self?.onUpdate?()
        }

        restoreStateIfNeeded()
    }

    func requestLocationWhenInUse() {
        locationService.requestWhenInUse()
    }

    func requestLocationAlways() {
        locationService.requestAlways()
    }

    func requestNotifications(completion: @escaping (Bool) -> Void) {
        notificationService.requestAuthorization(completion: completion)
    }

    func startOrResume() {
        if !isRunning {
            isRunning = true
            isPaused = false
            startTimeMs = nowMs()
            pausedAtMs = 0
            totalPausedMs = 0
            lastPaceCheckDistance = 0.0
            goalReached = false
            gpsDistanceKm = 0.0
            stepDistanceKm = 0.0
        } else if isPaused {
            isPaused = false
            if pausedAtMs > 0 {
                totalPausedMs += nowMs() - pausedAtMs
            }
            pausedAtMs = 0
        }

        locationService.start()
        pedometerService.start()
        startTicking()
        persistState()
        onUpdate?()
    }

    func pause() {
        guard isRunning, !isPaused else { return }
        isPaused = true
        pausedAtMs = nowMs()
        locationService.stop()
        pedometerService.stop()
        stopTicking()
        persistState()
        onUpdate?()
    }

    func stopAndReset() {
        if isRunning {
            saveHistoryEntry()
        }

        isRunning = false
        isPaused = false
        startTimeMs = 0
        pausedAtMs = 0
        totalPausedMs = 0
        gpsDistanceKm = 0.0
        stepDistanceKm = 0.0
        lastPaceCheckDistance = 0.0
        goalReached = false

        locationService.stop()
        pedometerService.stop()
        stopTicking()
        runtimeStore.clear()
        onUpdate?()
    }

    func currentElapsedMs() -> Int64 {
        guard isRunning, startTimeMs > 0 else { return 0 }
        let now = nowMs()
        let paused = totalPausedMs + ((isPaused && pausedAtMs > 0) ? (now - pausedAtMs) : 0)
        return max(0, now - startTimeMs - paused)
    }

    func currentDistanceKm() -> Double {
        return gpsDistanceKm + stepDistanceKm
    }

    func currentPaceText() -> String {
        return formatPaceMinPerKm(elapsedMs: currentElapsedMs(), distanceKm: currentDistanceKm())
    }

    func currentTimerText() -> String {
        return formatHms(elapsedMs: currentElapsedMs())
    }

    private func startTicking() {
        if timer != nil { return }
        timer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            self?.onUpdate?()
            self?.persistState()
        }
    }

    private func stopTicking() {
        timer?.invalidate()
        timer = nil
    }

    private func handleDistanceUpdate() {
        maybeAutoPauseOnTarget()
        maybeNotifyPace()
        persistState()
        onUpdate?()
    }

    private func maybeAutoPauseOnTarget() {
        if !isRunning || isPaused || goalReached { return }
        let targetDistance = parseDistanceKm(text: targetDistanceText)
        if targetDistance <= 0 { return }
        let epsilon = 0.001
        if currentDistanceKm() + epsilon >= targetDistance {
            goalReached = true
            deliverMessage("Goal reached. Workout paused.")
            pause()
        }
    }

    private func maybeNotifyPace() {
        let step = calculatePaceNotificationStep(currentDistanceKm: currentDistanceKm())
        if step == Double.greatestFiniteMagnitude { return }
        let distanceSinceLast = currentDistanceKm() - lastPaceCheckDistance
        if distanceSinceLast >= step && currentDistanceKm() > 0.1 {
            lastPaceCheckDistance = currentDistanceKm()
            checkAndCorrectPace(currentDistanceKm: currentDistanceKm())
        }
    }

    private func calculatePaceNotificationStep(currentDistanceKm: Double) -> Double {
        let targetDistance = parseDistanceKm(text: targetDistanceText)
        if targetDistance <= 0 { return Double.greatestFiniteMagnitude }
        let progressPercent = (currentDistanceKm / targetDistance) * 100.0
        if targetDistance < 10.0 { return targetDistance * 0.1 }
        if progressPercent < 80.0 { return targetDistance * 0.1 }
        if progressPercent < 90.0 { return 1.0 }
        return 0.5
    }

    private func checkAndCorrectPace(currentDistanceKm: Double) {
        let targetDistance = parseDistanceKm(text: targetDistanceText)
        let targetTime = parseTargetTimeSeconds(text: targetTimeText)
        if targetDistance <= 0 || targetTime <= 0 { return }

        let elapsedSeconds = Double(currentElapsedMs()) / 1000.0
        let currentPace = (currentDistanceKm > 0) ? (elapsedSeconds / currentDistanceKm) : 0

        let remainingDistance = targetDistance - currentDistanceKm
        let timeLeft = Double(targetTime) - elapsedSeconds
        let requiredPace = (remainingDistance > 0 && timeLeft > 0) ? (timeLeft / remainingDistance) : Double.greatestFiniteMagnitude

        let remainingDistanceText = formatRemainingDistance(km: remainingDistance)
        let currentPaceText = formatPace(secondsPerKm: currentPace)
        let requiredPaceText = formatPace(secondsPerKm: requiredPace)

        let message: String?
        if requiredPace < 180.0 || timeLeft < 0 {
            message = "Pace goal is no longer reachable. Consider adjusting your target."
        } else if currentPace > requiredPace + 5 {
            message = "You are behind pace. Current: \(currentPaceText), required: \(requiredPaceText). Remaining: \(remainingDistanceText)."
        } else if currentPace < requiredPace - 5 {
            message = "You are ahead of pace. Current: \(currentPaceText), required: \(requiredPaceText). Remaining: \(remainingDistanceText)."
        } else {
            message = nil
        }

        if let msg = message {
            deliverMessage(msg)
        }
    }

    private func saveHistoryEntry() {
        let elapsed = currentElapsedMs()
        let timeText = formatHms(elapsedMs: elapsed)
        let totalDistance = currentDistanceKm()
        let speed = (elapsed > 0 && totalDistance > 0) ? (totalDistance / (Double(elapsed) / 3600000.0)) : 0.0

        let dateText = formatDate()
        let entry = WorkoutHistory(
            date: dateText,
            distanceKm: totalDistance,
            timeText: timeText,
            speedKmh: speed,
            elapsedMs: elapsed
        )

        var history = store.loadHistory()
        history.insert(entry, at: 0)
        store.saveHistory(history)
    }

    private func persistState() {
        let state = WorkoutRuntimeState(
            gpsDistanceKm: gpsDistanceKm,
            stepDistanceKm: stepDistanceKm,
            lastPaceCheckDistance: lastPaceCheckDistance,
            goalReached: goalReached,
            startTimeMs: startTimeMs,
            pausedAtMs: pausedAtMs,
            totalPausedMs: totalPausedMs,
            isRunning: isRunning,
            isPaused: isPaused
        )
        runtimeStore.save(state)
    }

    private func restoreStateIfNeeded() {
        guard let state = runtimeStore.load() else { return }
        gpsDistanceKm = state.gpsDistanceKm
        stepDistanceKm = state.stepDistanceKm
        lastPaceCheckDistance = state.lastPaceCheckDistance
        goalReached = state.goalReached
        startTimeMs = state.startTimeMs
        pausedAtMs = state.pausedAtMs
        totalPausedMs = state.totalPausedMs
        isRunning = state.isRunning
        isPaused = state.isPaused

        if isRunning && !isPaused {
            locationService.start()
            pedometerService.start()
            startTicking()
        }
    }

    private func deliverMessage(_ text: String) {
        if isAppActive() {
            ttsService.speak(text)
        } else {
            notificationService.notify(title: "Stayer", body: text)
        }
    }

    private func isAppActive() -> Bool {
        return UIApplication.shared.applicationState == .active
    }

    private func formatDate() -> String {
        let fmt = DateFormatter()
        fmt.dateFormat = "dd.MM.yy"
        return fmt.string(from: Date())
    }

    private func parseDistanceKm(text: String) -> Double {
        let normalized = text.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: ",", with: ".")
        return Double(normalized) ?? 0.0
    }

    private func parseTargetTimeSeconds(text: String) -> Int {
        let parts = text.split(separator: ":")
        if parts.count != 3 { return 0 }
        let h = Int(parts[0]) ?? 0
        let m = Int(parts[1]) ?? 0
        let s = Int(parts[2]) ?? 0
        return h * 3600 + m * 60 + s
    }

    private func formatHms(elapsedMs: Int64) -> String {
        let totalSec = max(0, elapsedMs / 1000)
        let h = totalSec / 3600
        let m = (totalSec % 3600) / 60
        let s = totalSec % 60
        return String(format: "%02d:%02d:%02d", h, m, s)
    }

    private func formatPaceMinPerKm(elapsedMs: Int64, distanceKm: Double) -> String {
        if distanceKm <= 0.05 { return "--" }
        let sec = Double(elapsedMs) / 1000.0
        let secPerKm = sec / distanceKm
        if !secPerKm.isFinite || secPerKm <= 0 { return "--" }
        let total = Int(secPerKm)
        let min = total / 60
        let secPart = total % 60
        return String(format: "%d:%02d /km", min, secPart)
    }

    private func formatPace(secondsPerKm: Double) -> String {
        if secondsPerKm <= 0 || secondsPerKm == Double.greatestFiniteMagnitude { return "0 min 0 sec" }
        let totalSeconds = Int(secondsPerKm)
        let minutes = totalSeconds / 60
        let seconds = totalSeconds % 60
        let roundedSeconds = (seconds / 5) * 5
        if minutes == 0 { return "\(roundedSeconds) sec" }
        if roundedSeconds == 0 { return "\(minutes) min" }
        return "\(minutes) min \(roundedSeconds) sec"
    }

    private func formatRemainingDistance(km: Double) -> String {
        if km <= 0 { return "0 m" }
        let kilometers = Int(km)
        let meters = Int((km - Double(kilometers)) * 1000.0)
        if kilometers == 0 { return "\(meters) m" }
        if meters == 0 { return "\(kilometers) km" }
        return "\(kilometers) km \(meters) m"
    }

    private func nowMs() -> Int64 {
        return Int64(Date().timeIntervalSince1970 * 1000.0)
    }
}
