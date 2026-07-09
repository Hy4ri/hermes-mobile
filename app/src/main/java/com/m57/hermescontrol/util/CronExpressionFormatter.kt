package com.m57.hermescontrol.util

import com.m57.hermescontrol.BuildConfig

object CronExpressionFormatter {
    private val dowNames =
        listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
    private val monthNames =
        listOf(
            "",
            "January",
            "February",
            "March",
            "April",
            "May",
            "June",
            "July",
            "August",
            "September",
            "October",
            "November",
            "December",
        )

    fun cronToHumanReadable(schedule: String): String {
        val clean = schedule.trim().replace("\\s+".toRegex(), " ")
        val parts = clean.split(" ")
        if (parts.size != 5) {
            return schedule
        }

        try {
            val minutePart = parts[0]
            val hourPart = parts[1]
            val domPart = parts[2]
            val monthPart = parts[3]
            val dowPart = parts[4]

            parseSimpleShortcuts(minutePart, hourPart, domPart, monthPart, dowPart)?.let { return it }

            val hoursList = parseField(hourPart, 0, 23)
            val minutesList = parseField(minutePart, 0, 59)

            parseDayOfMonthAndMonth(domPart, monthPart, dowPart, hoursList, minutesList)?.let { return it }
            parseDaysOfWeek(dowPart, domPart, monthPart, hoursList, minutesList)?.let { return it }
            parseGeneralTime(domPart, monthPart, dowPart, hoursList, minutesList)?.let { return it }

            // Fallback
            return schedule
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                android.util.Log.w("CronExpressionFormatter", "Failed to parse cron: $schedule", e)
            }
            return "Raw: $schedule"
        }
    }

    private fun parseSimpleShortcuts(
        minutePart: String,
        hourPart: String,
        domPart: String,
        monthPart: String,
        dowPart: String,
    ): String? {
        if (minutePart.startsWith("*/") && hourPart == "*" && domPart == "*" && monthPart == "*" && dowPart == "*") {
            val step = minutePart.substring(2).toIntOrNull()
            if (step != null) return "Every $step minutes"
        }
        if (minutePart == "0" && hourPart.startsWith("*/") && domPart == "*" && monthPart == "*" && dowPart == "*") {
            val step = hourPart.substring(2).toIntOrNull()
            if (step != null) return "Every $step hours"
        }
        if (minutePart == "0" && hourPart == "0" && domPart == "*" && monthPart == "*" && dowPart == "*") {
            return "Every day at midnight"
        }
        return null
    }

    private fun parseDayOfMonthAndMonth(
        domPart: String,
        monthPart: String,
        dowPart: String,
        hoursList: List<Int>,
        minutesList: List<Int>,
    ): String? {
        if (domPart != "*" && monthPart != "*" && dowPart == "*") {
            val doms = parseField(domPart, 1, 31)
            val months = parseField(monthPart, 1, 12)
            if (doms.size == 1 && months.size == 1 && hoursList.size == 1 && minutesList.size == 1) {
                val timeStr = formatTime(hoursList[0], minutesList[0])
                val monthStr = monthNames[months[0]]
                val domStr = formatOrdinal(doms[0])
                return if (timeStr == "midnight") {
                    "On $monthStr $domStr at midnight"
                } else {
                    "On $monthStr $domStr at $timeStr"
                }
            }
        }
        if (domPart != "*" && monthPart == "*" && dowPart == "*") {
            val doms = parseField(domPart, 1, 31)
            if (doms.size == 1 && hoursList.size == 1 && minutesList.size == 1) {
                val timeStr = formatTime(hoursList[0], minutesList[0])
                val domStr = formatOrdinal(doms[0])
                return "On the $domStr of every month at $timeStr"
            }
        }
        return null
    }

    private fun parseDaysOfWeek(
        dowPart: String,
        domPart: String,
        monthPart: String,
        hoursList: List<Int>,
        minutesList: List<Int>,
    ): String? {
        if (dowPart == "*" || domPart != "*" || monthPart != "*") return null
        if (hoursList.size != 1 || minutesList.size != 1) return null

        val timeStr = formatTime(hoursList[0], minutesList[0])

        if (dowPart.contains("-")) {
            val bounds = dowPart.split("-")
            if (bounds.size == 2) {
                val start = bounds[0].toIntOrNull() ?: -1
                val end = bounds[1].toIntOrNull() ?: -1
                if (start in 0..7 && end in 0..7) {
                    val startName = dowNames[start]
                    val endName = dowNames[end]
                    return "At $timeStr $startName through $endName"
                }
            }
        }

        val dows = parseField(dowPart, 0, 7)
        if (dows.size == 1) {
            val dowName = dowNames[dows[0]]
            return "Every $dowName at $timeStr"
        }

        return null
    }

    private fun parseGeneralTime(
        domPart: String,
        monthPart: String,
        dowPart: String,
        hoursList: List<Int>,
        minutesList: List<Int>,
    ): String? {
        if (domPart == "*" && monthPart == "*" && dowPart == "*") {
            if (hoursList.isNotEmpty() && minutesList.size == 1) {
                val timeStrings = hoursList.map { formatTime(it, minutesList[0]) }
                val timeDesc =
                    if (timeStrings.size > 1) {
                        timeStrings.dropLast(1).joinToString(", ") + " and " + timeStrings.last()
                    } else {
                        timeStrings[0]
                    }
                return "At $timeDesc every day"
            }
        }
        return null
    }

    private fun formatTime(
        h: Int,
        m: Int,
    ): String {
        if (h == 0 && m == 0) return "midnight"
        return String.format("%02d:%02d", h, m)
    }

    private fun formatOrdinal(d: Int): String =
        when {
            d % 100 in 11..13 -> "${d}th"
            d % 10 == 1 -> "${d}st"
            d % 10 == 2 -> "${d}nd"
            d % 10 == 3 -> "${d}rd"
            else -> "${d}th"
        }

    private fun parseField(
        field: String,
        min: Int,
        max: Int,
    ): List<Int> {
        if (field == "*") return (min..max).toList()
        if (field.contains(",")) {
            return field
                .split(",")
                .flatMap { parseField(it, min, max) }
                .distinct()
                .sorted()
        }
        if (field.contains("-")) {
            val parts = field.split("-")
            if (parts.size == 2) {
                val start = parts[0].toIntOrNull() ?: return emptyList()
                val end = parts[1].toIntOrNull() ?: return emptyList()
                return (start..end).toList()
            }
        }
        val single = field.toIntOrNull()
        if (single != null) {
            return listOf(single)
        }
        return emptyList()
    }
}
