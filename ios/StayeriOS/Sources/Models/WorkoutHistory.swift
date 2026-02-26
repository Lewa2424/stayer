import Foundation

struct WorkoutHistory: Codable {
    let date: String
    let distanceKm: Double
    let timeText: String
    let speedKmh: Double
    let elapsedMs: Int64
}
