package com.example.stayer.health

import android.content.Context
import android.os.Build
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant

object HealthConnectManager {

    val PERMISSIONS = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class)
    )

    /**
     * Returns SDK status code. Callers should compare against
     * HealthConnectClient.SDK_AVAILABLE / SDK_UNAVAILABLE / SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED.
     */
    fun getSdkStatus(context: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return HealthConnectClient.SDK_UNAVAILABLE
        }
        return HealthConnectClient.getSdkStatus(context)
    }

    fun isAvailable(context: Context): Boolean =
        getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    suspend fun hasPermissions(context: Context): Boolean {
        if (!isAvailable(context)) return false
        return try {
            val client = HealthConnectClient.getOrCreate(context)
            val granted = client.permissionController.getGrantedPermissions()
            granted.containsAll(PERMISSIONS)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Read the most recent HeartRateRecord after [startTimeMs].
     * Returns the record (with samples list and time metadata) or null.
     */
    suspend fun readLatestHeartRate(context: Context, startTimeMs: Long): HeartRateRecord? {
        if (!isAvailable(context)) return null
        return try {
            val client = HealthConnectClient.getOrCreate(context)
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = HeartRateRecord::class,
                    timeRangeFilter = TimeRangeFilter.after(Instant.ofEpochMilli(startTimeMs)),
                    ascendingOrder = false,
                    pageSize = 1
                )
            )
            response.records.firstOrNull()
        } catch (_: Exception) {
            null
        }
    }
}
