import Foundation

struct WorkoutRuntimeState: Codable {
    var gpsDistanceKm: Double
    var stepDistanceKm: Double
    var lastPaceCheckDistance: Double
    var goalReached: Bool
    var startTimeMs: Int64
    var pausedAtMs: Int64
    var totalPausedMs: Int64
    var isRunning: Bool
    var isPaused: Bool
}

final class WorkoutRuntimeStore {
    private let defaults = UserDefaults.standard
    private let runtimeKey = "workout_runtime_state"

    func load() -> WorkoutRuntimeState? {
        guard let data = defaults.data(forKey: runtimeKey) else { return nil }
        return try? JSONDecoder().decode(WorkoutRuntimeState.self, from: data)
    }

    func save(_ state: WorkoutRuntimeState) {
        if let data = try? JSONEncoder().encode(state) {
            defaults.set(data, forKey: runtimeKey)
        }
    }

    func clear() {
        defaults.removeObject(forKey: runtimeKey)
    }
}
