package com.example.util

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Bilingual Persian (fa-IR) & English (en-US) formatting utilities:
 * - Converts Latin numerals to Persian numerals (۰۱۲۳۴۵۶۷۸۹) when requested
 * - Formats Gregorian and Solar Hijri (Jalali) dates accurately
 * - Formats network byte counters, speeds, and session durations
 */
object LocalizationHelper {
    private val persianDigits = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')

    fun formatDigits(input: String, usePersianDigits: Boolean): String {
        if (!usePersianDigits) return input
        val sb = StringBuilder(input.length)
        for (ch in input) {
            if (ch in '0'..'9') {
                sb.append(persianDigits[ch - '0'])
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun formatBytes(bytes: Long, usePersianDigits: Boolean = false): String {
        val b = bytes.coerceAtLeast(0L)
        val raw = when {
            b < 1024L -> "$b B"
            b < 1024L * 1024L -> String.format(Locale.US, "%.1f KB", b / 1024.0)
            b < 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f MB", b / (1024.0 * 1024.0))
            else -> String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024.0 * 1024.0))
        }
        return formatDigits(raw, usePersianDigits)
    }

    fun formatSpeed(bytesPerSec: Long, usePersianDigits: Boolean = false): String {
        return "${formatBytes(bytesPerSec, usePersianDigits)}/s"
    }

    fun formatDuration(connectedSinceMs: Long?, nowMs: Long = System.currentTimeMillis(), usePersianDigits: Boolean = false): String {
        if (connectedSinceMs == null || nowMs < connectedSinceMs) {
            return formatDigits("00:00:00", usePersianDigits)
        }
        val totalSeconds = (nowMs - connectedSinceMs) / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        val formatted = String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        return formatDigits(formatted, usePersianDigits)
    }

    fun formatTimestamp(timestampMs: Long?, useJalaliAndPersian: Boolean): String {
        if (timestampMs == null || timestampMs <= 0L) return "—"
        if (!useJalaliAndPersian) {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            return sdf.format(Date(timestampMs))
        }
        val cal = Calendar.getInstance().apply { timeInMillis = timestampMs }
        val gy = cal.get(Calendar.YEAR)
        val gm = cal.get(Calendar.MONTH) + 1
        val gd = cal.get(Calendar.DAY_OF_MONTH)
        val hh = cal.get(Calendar.HOUR_OF_DAY)
        val mm = cal.get(Calendar.MINUTE)
        val (jy, jm, jd) = gregorianToJalali(gy, gm, gd)
        val raw = String.format(Locale.US, "%04d/%02d/%02d %02d:%02d", jy, jm, jd, hh, mm)
        return formatDigits(raw, true)
    }

    fun gregorianToJalali(gy: Int, gm: Int, gd: Int): Triple<Int, Int, Int> {
        val gDaysInMonth = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val gy2 = if (gm > 2) gy + 1 else gy
        var days = 355666 + (365 * gy) + ((gy2 + 3) / 4) - ((gy2 + 99) / 100) + ((gy2 + 399) / 400) + gd + gDaysInMonth[gm - 1]
        var jy = -1595 + (33 * (days / 12053))
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) {
            jy += (days - 1) / 365
            days = (days - 1) % 365
        }
        val jm: Int
        val jd: Int
        if (days < 186) {
            jm = 1 + (days / 31)
            jd = 1 + (days % 31)
        } else {
            jm = 7 + ((days - 186) / 30)
            jd = 1 + ((days - 186) % 30)
        }
        return Triple(jy, jm, jd)
    }
}
