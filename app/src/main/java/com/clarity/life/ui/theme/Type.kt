package com.clarity.life.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.clarity.life.R

/** Space Grotesk, bundled with the APK under the SIL Open Font License (app/licenses). */
val SpaceGrotesk = FontFamily(
    Font(R.font.space_grotesk_regular, FontWeight.Normal),
    Font(R.font.space_grotesk_medium, FontWeight.Medium),
    Font(R.font.space_grotesk_bold, FontWeight.Bold),
)

private fun style(size: Int, weight: FontWeight, lineHeight: Int, letterSpacing: Double = 0.0) = TextStyle(
    fontFamily = SpaceGrotesk,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
)

val ClarityTypography = Typography(
    displaySmall = style(32, FontWeight.Bold, 38, (-0.5)),
    headlineMedium = style(26, FontWeight.Bold, 32, (-0.3)),
    headlineSmall = style(22, FontWeight.Bold, 28),
    titleLarge = style(19, FontWeight.Medium, 25),
    titleMedium = style(16, FontWeight.Medium, 22),
    bodyLarge = style(16, FontWeight.Normal, 24),
    bodyMedium = style(15, FontWeight.Normal, 23),
    bodySmall = style(13, FontWeight.Normal, 19),
    labelLarge = style(15, FontWeight.Medium, 20),
    labelMedium = style(13, FontWeight.Medium, 17),
    labelSmall = style(12, FontWeight.Medium, 16, 0.4),
)
