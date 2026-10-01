package com.angeljo0801.calendario

import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import java.util.Locale

object DateTimeParser {

    data class Result(
        val date: LocalDate? = null,
        val time: LocalTime? = null
    )

    private val months = mapOf(
        "enero" to 1, "january" to 1,
        "febrero" to 2, "february" to 2,
        "marzo" to 3, "march" to 3,
        "abril" to 4, "april" to 4,
        "mayo" to 5, "may" to 5,
        "junio" to 6, "june" to 6,
        "julio" to 7, "july" to 7,
        "agosto" to 8, "august" to 8,
        "septiembre" to 9, "setiembre" to 9, "september" to 9,
        "octubre" to 10, "october" to 10,
        "noviembre" to 11, "november" to 11,
        "diciembre" to 12, "december" to 12
    )

    private val weekdays = mapOf(
        "lunes" to DayOfWeek.MONDAY, "monday" to DayOfWeek.MONDAY,
        "martes" to DayOfWeek.TUESDAY, "tuesday" to DayOfWeek.TUESDAY,
        "miercoles" to DayOfWeek.WEDNESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
        "jueves" to DayOfWeek.THURSDAY, "thursday" to DayOfWeek.THURSDAY,
        "viernes" to DayOfWeek.FRIDAY, "friday" to DayOfWeek.FRIDAY,
        "sabado" to DayOfWeek.SATURDAY, "saturday" to DayOfWeek.SATURDAY,
        "domingo" to DayOfWeek.SUNDAY, "sunday" to DayOfWeek.SUNDAY
    )

    fun parse(text: String, today: LocalDate = LocalDate.now()): Result {
        val normalized = normalize(text)
        return Result(
            date = parseDate(normalized, today),
            time = parseTime(normalized)
        )
    }

    private fun parseDate(text: String, today: LocalDate): LocalDate? {
        when {
            Regex("\\b(pasado manana|day after tomorrow)\\b").containsMatchIn(text) -> return today.plusDays(2)
            Regex("\\b(manana|tomorrow)\\b").containsMatchIn(text) -> return today.plusDays(1)
            Regex("\\b(hoy|today)\\b").containsMatchIn(text) -> return today
        }

        val namedMonthRegex = Regex(
            "\\b(\\d{1,2})\\s*(?:de\\s+)?(" + months.keys.joinToString("|") + ")" +
                "(?:\\s*(?:de\\s+|,\\s*)?(\\d{4}))?\\b"
        )
        namedMonthRegex.find(text)?.let { match ->
            val day = match.groupValues[1].toIntOrNull() ?: return@let
            val month = months[match.groupValues[2]] ?: return@let
            val year = match.groupValues[3].toIntOrNull() ?: today.year
            runCatching { LocalDate.of(year, month, day) }.getOrNull()?.let { return it }
        }

        val numericRegex = Regex("\\b(\\d{1,2})[/-](\\d{1,2})(?:[/-](\\d{2,4}))?\\b")
        numericRegex.find(text)?.let { match ->
            val first = match.groupValues[1].toIntOrNull() ?: return@let
            val second = match.groupValues[2].toIntOrNull() ?: return@let
            val rawYear = match.groupValues[3].toIntOrNull()
            val year = when {
                rawYear == null -> today.year
                rawYear < 100 -> 2000 + rawYear
                else -> rawYear
            }

            val usOrder = Locale.getDefault().country.equals("US", ignoreCase = true)
            val month = if (usOrder) first else second
            val day = if (usOrder) second else first
            runCatching { LocalDate.of(year, month, day) }.getOrNull()?.let { return it }
        }

        weekdays.forEach { (word, dayOfWeek) ->
            if (Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(text)) {
                return today.with(TemporalAdjusters.nextOrSame(dayOfWeek))
            }
        }

        return null
    }

    private fun parseTime(text: String): LocalTime? {
        val compact = text.replace(".", "")

        val twelveHour = Regex("\\b(\\d{1,2})(?::([0-5]\\d))?\\s*(am|pm)\\b")
        twelveHour.find(compact)?.let { match ->
            var hour = match.groupValues[1].toIntOrNull() ?: return@let
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            if (hour !in 1..12) return@let
            val marker = match.groupValues[3]
            if (marker == "pm" && hour != 12) hour += 12
            if (marker == "am" && hour == 12) hour = 0
            return LocalTime.of(hour, minute)
        }

        val twentyFourHour = Regex("\\b([01]?\\d|2[0-3])[:h]([0-5]\\d)\\b")
        twentyFourHour.find(compact)?.let { match ->
            val hour = match.groupValues[1].toIntOrNull() ?: return@let
            val minute = match.groupValues[2].toIntOrNull() ?: return@let
            return LocalTime.of(hour, minute)
        }

        return null
    }

    private fun normalize(value: String): String {
        val noAccents = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return noAccents.lowercase(Locale.ROOT)
    }
}
