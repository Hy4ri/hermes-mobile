package com.m57.hermescontrol.ui.sessions.components

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.m57.hermescontrol.R
import com.m57.hermescontrol.ui.sessions.SessionAge
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Compact age shown on the card: "now", "15m", "3h", "2d", or a short date ("Sep 3"). */
@Composable
fun sessionAgeLabel(age: SessionAge): String =
    when (age) {
        SessionAge.Now -> {
            stringResource(R.string.session_age_now)
        }

        is SessionAge.Minutes -> {
            stringResource(R.string.session_age_minutes, age.value.toInt())
        }

        is SessionAge.Hours -> {
            stringResource(R.string.session_age_hours, age.value.toInt())
        }

        is SessionAge.Days -> {
            stringResource(R.string.session_age_days, age.value.toInt())
        }

        is SessionAge.Date -> {
            val locale = LocalConfiguration.current.locales[0]
            val skeleton = if (age.sameYear) "MMMd" else "yMMMd"
            DateTimeFormatter
                .ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
                .format(age.date)
        }
    }

/**
 * Spoken form of [sessionAgeLabel]. Screen readers misread micro units ("15m" as
 * fifteen meters), so TalkBack gets the full phrase instead.
 */
@Composable
fun sessionAgeDescription(age: SessionAge): String =
    when (age) {
        SessionAge.Now -> {
            stringResource(R.string.session_age_now_desc)
        }

        is SessionAge.Minutes -> {
            pluralStringResource(R.plurals.session_age_minutes_desc, age.value.toInt(), age.value.toInt())
        }

        is SessionAge.Hours -> {
            pluralStringResource(R.plurals.session_age_hours_desc, age.value.toInt(), age.value.toInt())
        }

        is SessionAge.Days -> {
            pluralStringResource(R.plurals.session_age_days_desc, age.value.toInt(), age.value.toInt())
        }

        is SessionAge.Date -> {
            val locale = LocalConfiguration.current.locales[0]
            val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale).format(age.date)
            stringResource(R.string.session_age_date_desc, date)
        }
    }
