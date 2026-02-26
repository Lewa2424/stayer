import Foundation

final class WorkoutStore {
    private let defaults = UserDefaults.standard

    private let targetDistanceKey = "TARGET_DISTANCE"
    private let targetTimeKey = "TARGET_TIME"
    private let historyKey = "workoutHistoryList"
    private let setupDoneKey = "setup_done"

    func loadTargetDistanceText() -> String {
        return defaults.string(forKey: targetDistanceKey) ?? "0"
    }

    func loadTargetTimeText() -> String {
        return defaults.string(forKey: targetTimeKey) ?? "0"
    }

    func saveTargetDistanceText(_ value: String) {
        defaults.set(value, forKey: targetDistanceKey)
    }

    func saveTargetTimeText(_ value: String) {
        defaults.set(value, forKey: targetTimeKey)
    }

    func isSetupDone() -> Bool {
        return defaults.bool(forKey: setupDoneKey)
    }

    func markSetupDone() {
        defaults.set(true, forKey: setupDoneKey)
    }

    func loadHistory() -> [WorkoutHistory] {
        guard let data = defaults.data(forKey: historyKey) else { return [] }
        do {
            return try JSONDecoder().decode([WorkoutHistory].self, from: data)
        } catch {
            return []
        }
    }

    func saveHistory(_ history: [WorkoutHistory]) {
        do {
            let data = try JSONEncoder().encode(history)
            defaults.set(data, forKey: historyKey)
        } catch {
            // Swallow errors; storage is best-effort.
        }
    }
}
