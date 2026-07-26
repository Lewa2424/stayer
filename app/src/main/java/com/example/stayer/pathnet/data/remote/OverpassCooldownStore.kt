package com.example.stayer.pathnet.data.remote

import android.content.Context

/**
 * Хранит окончание блокировки Overpass между запусками приложения.
 * Persists the Overpass cooldown deadline across app restarts.
 */
internal class OverpassCooldownStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    /**
     * Возвращает сохранённый срок блокировки в Unix-времени (мс).
     * Returns the persisted cooldown deadline as Unix time in milliseconds.
     */
    fun loadUntilMillis(): Long {
        return preferences.getLong(KEY_UNTIL_MILLIS, 0L).coerceAtLeast(0L)
    }

    /**
     * Продлевает блокировку, не сокращая уже полученный срок Retry-After.
     * Extends the cooldown without shortening an existing Retry-After deadline.
     */
    fun extendUntilMillis(untilMillis: Long) {
        synchronized(preferences) {
            val current = preferences.getLong(KEY_UNTIL_MILLIS, 0L)
            if (untilMillis > current) {
                preferences.edit().putLong(KEY_UNTIL_MILLIS, untilMillis).apply()
            }
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "overpass_request_guard"
        const val KEY_UNTIL_MILLIS = "cooldown_until_millis"
    }
}
