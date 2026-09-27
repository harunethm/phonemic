package com.scylla.tool.phonemic.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.scylla.tool.phonemic.R

// sora.ttf / ibm_plex_sans.ttf are variable-axis fonts (no static weight instances shipped
// upstream); FontVariation.weight() picks the instance per TextStyle. Ignored pre-API 26.
@OptIn(ExperimentalTextApi::class)
val SoraFamily = FontFamily(
    Font(R.font.sora, weight = FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.sora, weight = FontWeight.ExtraBold, variationSettings = FontVariation.Settings(FontVariation.weight(800)))
)

@OptIn(ExperimentalTextApi::class)
val PlexSansFamily = FontFamily(
    Font(R.font.ibm_plex_sans, weight = FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.ibm_plex_sans, weight = FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.ibm_plex_sans, weight = FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.ibm_plex_sans, weight = FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700)))
)

val PlexMonoFamily = FontFamily(
    Font(R.font.ibm_plex_mono, weight = FontWeight.Normal),
    Font(R.font.ibm_plex_mono_medium, weight = FontWeight.Medium)
)

private val base = Typography()

// Sora for display headings, IBM Plex Sans everywhere else - matches the UI spec's font stack.
val Typography = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = PlexSansFamily),
    displayMedium = base.displayMedium.copy(fontFamily = PlexSansFamily),
    displaySmall = base.displaySmall.copy(fontFamily = PlexSansFamily),
    headlineLarge = base.headlineLarge.copy(fontFamily = SoraFamily, fontWeight = FontWeight.ExtraBold),
    headlineMedium = base.headlineMedium.copy(
        fontFamily = SoraFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 26.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp
    ),
    headlineSmall = base.headlineSmall.copy(fontFamily = SoraFamily, fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(fontFamily = PlexSansFamily, fontSize = 14.sp),
    bodyMedium = base.bodyMedium.copy(fontFamily = PlexSansFamily),
    bodySmall = base.bodySmall.copy(fontFamily = PlexSansFamily),
    labelLarge = base.labelLarge.copy(fontFamily = PlexSansFamily, fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontFamily = PlexSansFamily, fontWeight = FontWeight.Medium),
    labelSmall = base.labelSmall.copy(
        fontFamily = PlexSansFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        letterSpacing = 0.8.sp
    )
)
