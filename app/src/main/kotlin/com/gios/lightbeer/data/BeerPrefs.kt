package com.gios.lightbeer.data

import android.content.Context
import java.time.LocalDate

/**
 * "Beers today" is the whole payoff of the joke, so it has to survive a relaunch. Stored
 * as an epoch day plus a count rather than a timestamp list — same pattern LightNotebook
 * uses for its calendar entries — so the count silently resets at local midnight without
 * a background job or an alarm to schedule.
 */
class BeerPrefs(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Count for today, or 0 if the stored count belongs to an earlier day. */
    fun todayCount(): Int {
        val today = LocalDate.now().toEpochDay()
        val storedDay = prefs.getLong(KEY_DAY, -1L)
        return if (storedDay == today) prefs.getInt(KEY_COUNT, 0) else 0
    }

    /** Call once per finished glass. Returns the new total for today. */
    fun recordBeer(): Int {
        val today = LocalDate.now().toEpochDay()
        val storedDay = prefs.getLong(KEY_DAY, -1L)
        val next = if (storedDay == today) prefs.getInt(KEY_COUNT, 0) + 1 else 1
        prefs.edit().putLong(KEY_DAY, today).putInt(KEY_COUNT, next).apply()
        return next
    }

    fun reset() {
        prefs.edit().putLong(KEY_DAY, LocalDate.now().toEpochDay()).putInt(KEY_COUNT, 0).apply()
    }

    private companion object {
        const val PREFS_NAME = "lightbeer"
        const val KEY_DAY = "day"
        const val KEY_COUNT = "count"
    }
}
