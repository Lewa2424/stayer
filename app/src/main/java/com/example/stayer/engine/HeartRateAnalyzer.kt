package com.example.stayer.engine

enum class HeartRateZone { NONE, GREEN, YELLOW, RED }

data class HrAlertResult(
    val text: String,
    val isRedZone: Boolean
)

/**
 * State machine for heart rate zone tracking.
 * Returns alert text only on zone transitions or after a 120s cooldown in RED.
 */
class HeartRateAnalyzer(
    private val greenZoneMax: Int = 140,
    private val yellowZoneMax: Int = 160
) {
    private var lastZone: HeartRateZone = HeartRateZone.NONE
    private var lastRedAlertTimeMs: Long = 0L

    fun processBpm(bpm: Int, currentTimeMs: Long): HrAlertResult? {
        val zone = when {
            bpm <= greenZoneMax -> HeartRateZone.GREEN
            bpm <= yellowZoneMax -> HeartRateZone.YELLOW
            else -> HeartRateZone.RED
        }

        var text: String? = null
        var isRed = false

        if (zone != lastZone) {
            when (zone) {
                HeartRateZone.GREEN -> text = "Пульс в норме."
                HeartRateZone.YELLOW -> text = "Пульс в жёлтой зоне."
                HeartRateZone.RED -> {
                    text = "Внимание! Пульс в красной зоне."
                    isRed = true
                }
                else -> {}
            }
            lastZone = zone
        } else if (zone == HeartRateZone.RED) {
            if (currentTimeMs - lastRedAlertTimeMs > 120_000L) {
                text = "Внимание! Высокий пульс."
                isRed = true
            }
        }

        return if (text != null) HrAlertResult(text, isRed) else null
    }

    /**
     * Call after speak() attempt. Only updates cooldown timer if the alert was actually delivered.
     */
    fun onAlertDeliveryResult(isDelivered: Boolean, isRedZone: Boolean, currentTimeMs: Long) {
        if (isDelivered && isRedZone) {
            lastRedAlertTimeMs = currentTimeMs
        }
    }
}
