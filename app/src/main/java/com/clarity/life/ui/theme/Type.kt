package com.clarity.life.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.clarity.life.R

/** Instrument Sans, for everything he reads and taps. Bundled under the SIL Open Font License (app/licenses). */
val InstrumentSans = FontFamily(
    Font(R.font.instrument_sans_regular, FontWeight.Normal),
    Font(R.font.instrument_sans_medium, FontWeight.Medium),
    Font(R.font.instrument_sans_semibold, FontWeight.SemiBold),
    Font(R.font.instrument_sans_bold, FontWeight.Bold),
)

/** Instrument Serif, only for headlines, big numbers and the coach's longer writing (SIL OFL, app/licenses). */
val InstrumentSerif = FontFamily(
    Font(R.font.instrument_serif_regular, FontWeight.Normal),
    Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic),
)

private fun sans(size: Int, weight: FontWeight, lineHeight: Int, letterSpacing: Double = 0.0) = TextStyle(
    fontFamily = InstrumentSans,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
)

private fun serif(size: Int, lineHeight: Int, letterSpacing: Double = 0.0) = TextStyle(
    fontFamily = InstrumentSerif,
    fontWeight = FontWeight.Normal,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
)

val ClarityTypography = Typography(
    displayLarge = serif(46, 48, -0.4),
    displayMedium = serif(42, 44, -0.4),
    displaySmall = serif(36, 40, -0.3),
    headlineLarge = serif(32, 36, -0.2),
    headlineMedium = serif(28, 32, -0.2),
    headlineSmall = serif(24, 29),
    titleLarge = sans(19, FontWeight.SemiBold, 25),
    titleMedium = sans(16, FontWeight.Medium, 22),
    titleSmall = sans(15, FontWeight.Medium, 20),
    bodyLarge = sans(16, FontWeight.Normal, 24),
    bodyMedium = sans(15, FontWeight.Normal, 23),
    bodySmall = sans(13, FontWeight.Normal, 19),
    labelLarge = sans(15, FontWeight.Medium, 20),
    labelMedium = sans(13, FontWeight.Medium, 17),
    labelSmall = sans(12, FontWeight.SemiBold, 16, 0.8),
)
