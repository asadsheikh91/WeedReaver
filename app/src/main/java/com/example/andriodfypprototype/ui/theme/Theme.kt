package com.example.andriodfypprototype.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * WeedReaver palette.
 *
 * Warm cream grounds with a deep forest primary. The field interface is used in direct
 * sunlight, so the page is never pure white (glare) and body ink never drops below 7:1.
 * Ochre and clay are drawn from the same earth palette as the imagery so status colour
 * reads as part of the landscape rather than as alarm-red chrome.
 */
object Wr {
    // grounds
    val Cream = Color(0xFFF5F0E6)
    val CreamDeep = Color(0xFFECE4D4)
    val Ivory = Color(0xFFFFFCF6)
    val Line = Color(0xFFE6DCCB)
    val LineStrong = Color(0xFFD5C8B2)

    // ink
    val Ink = Color(0xFF1B2A21)
    val Ink2 = Color(0xFF566459)
    val Ink3 = Color(0xFF8B9387)

    // forest
    val Forest = Color(0xFF1E4A33)
    val ForestDeep = Color(0xFF143524)
    val Forest2 = Color(0xFF2D6547)
    val Moss = Color(0xFF4F8B5C)
    val Sage = Color(0xFFDCE6D2)
    val SageTint = Color(0xFFEBF0E2)
    val SageLine = Color(0xFFC9D8BD)

    // wheat / ochre
    val Wheat = Color(0xFFC9983D)
    val WheatInk = Color(0xFF7A5414)
    val WheatBg = Color(0xFFF4E8CE)
    val WheatLine = Color(0xFFE8D3A5)

    // clay / danger
    val Clay = Color(0xFFB9552F)
    val ClayInk = Color(0xFF933F1D)
    val ClayBg = Color(0xFFF5E1D5)
    val ClayLine = Color(0xFFEBC7B4)

    // slate / info
    val Slate = Color(0xFF3D697D)
    val SlateBg = Color(0xFFE2EAEB)

    // night surfaces (camera, navigation header)
    val Night = Color(0xFF0F1D15)
    val Night2 = Color(0xFF1A2E22)

    // heat ramp, tuned to sit over aerial imagery at ~60% opacity
    val HeatLow = Color(0xFF9BD08A)
    val HeatMid = Color(0xFFF3C04A)
    val HeatHigh = Color(0xFFE4552D)
    val HeatAbstain = Color(0xFFA7A3C4)

    // map chrome
    val Puck = Color(0xFF2F7FD8)
    val Route = Color(0xFFFFF6DE)

    val Scrim = Color(0x99101A14)
}

object WrShape {
    val xs = RoundedCornerShape(8.dp)
    val sm = RoundedCornerShape(12.dp)
    val md = RoundedCornerShape(16.dp)
    val lg = RoundedCornerShape(22.dp)
    val xl = RoundedCornerShape(28.dp)
    val pill = RoundedCornerShape(50)
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
}

private val Serif = FontFamily.Serif
private val Sans = FontFamily.SansSerif

private val trim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)

object WrType {
    val DisplayL = TextStyle(
        fontFamily = Serif, fontSize = 32.sp, lineHeight = 38.sp,
        fontWeight = FontWeight.Normal, letterSpacing = (-0.4).sp, color = Wr.Ink
    )
    val DisplayM = TextStyle(
        fontFamily = Serif, fontSize = 26.sp, lineHeight = 32.sp,
        fontWeight = FontWeight.Normal, letterSpacing = (-0.3).sp, color = Wr.Ink
    )
    val DisplayS = TextStyle(
        fontFamily = Serif, fontSize = 21.sp, lineHeight = 27.sp,
        fontWeight = FontWeight.Normal, letterSpacing = (-0.2).sp, color = Wr.Ink
    )
    val TitleL = TextStyle(
        fontFamily = Sans, fontSize = 18.sp, lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp, color = Wr.Ink
    )
    val TitleM = TextStyle(
        fontFamily = Sans, fontSize = 16.sp, lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold, color = Wr.Ink
    )
    val TitleS = TextStyle(
        fontFamily = Sans, fontSize = 14.sp, lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold, color = Wr.Ink
    )
    val Body = TextStyle(fontFamily = Sans, fontSize = 15.sp, lineHeight = 22.sp, color = Wr.Ink)
    val BodyS = TextStyle(fontFamily = Sans, fontSize = 13.sp, lineHeight = 19.sp, color = Wr.Ink2)
    val Caption = TextStyle(fontFamily = Sans, fontSize = 12.sp, lineHeight = 16.sp, color = Wr.Ink3)
    val Label = TextStyle(
        fontFamily = Sans, fontSize = 13.sp, lineHeight = 16.sp,
        fontWeight = FontWeight.Medium, color = Wr.Ink, lineHeightStyle = trim
    )
    val Overline = TextStyle(
        fontFamily = Sans, fontSize = 11.sp, lineHeight = 14.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = 0.9.sp, color = Wr.Ink3
    )
    val Button = TextStyle(
        fontFamily = Sans, fontSize = 15.sp, lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp, lineHeightStyle = trim
    )
    val NumXL = TextStyle(
        fontFamily = Sans, fontSize = 44.sp, lineHeight = 48.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = (-1).sp, fontFeatureSettings = "tnum", color = Wr.Ink
    )
    val NumL = TextStyle(
        fontFamily = Sans, fontSize = 26.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.5).sp, fontFeatureSettings = "tnum", color = Wr.Ink
    )
    val NumM = TextStyle(
        fontFamily = Sans, fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp, fontFeatureSettings = "tnum", color = Wr.Ink
    )
    val Mono = TextStyle(
        fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp, color = Wr.Ink2
    )
}

/** Motion tokens. Standard easing for layout, springs for anything the finger touches. */
object WrMotion {
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val Decelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val Accelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
    fun <T> quick() = tween<T>(180, easing = Emphasized)
    fun <T> standard() = tween<T>(320, easing = Emphasized)
    fun <T> slow() = tween<T>(520, easing = Emphasized)
    fun <T> press() = spring<T>(dampingRatio = 0.6f, stiffness = 900f)
    fun <T> settle() = spring<T>(dampingRatio = 0.85f, stiffness = 380f)
}

private val scheme = lightColorScheme(
    primary = Wr.Forest,
    onPrimary = Color.White,
    primaryContainer = Wr.Sage,
    onPrimaryContainer = Wr.ForestDeep,
    secondary = Wr.Moss,
    onSecondary = Color.White,
    secondaryContainer = Wr.SageTint,
    onSecondaryContainer = Wr.Forest,
    tertiary = Wr.Wheat,
    tertiaryContainer = Wr.WheatBg,
    onTertiaryContainer = Wr.WheatInk,
    background = Wr.Cream,
    onBackground = Wr.Ink,
    surface = Wr.Ivory,
    onSurface = Wr.Ink,
    surfaceVariant = Wr.CreamDeep,
    onSurfaceVariant = Wr.Ink2,
    surfaceContainerLowest = Wr.Ivory,
    surfaceContainerLow = Wr.Ivory,
    surfaceContainer = Wr.Ivory,
    surfaceContainerHigh = Wr.Ivory,
    surfaceContainerHighest = Wr.CreamDeep,
    surfaceBright = Wr.Ivory,
    outline = Wr.LineStrong,
    outlineVariant = Wr.Line,
    error = Wr.Clay,
    errorContainer = Wr.ClayBg,
    onErrorContainer = Wr.ClayInk,
    inverseSurface = Wr.Night2,
    inverseOnSurface = Wr.Cream,
    inversePrimary = Wr.Sage,
    scrim = Color(0xFF0B140F)
)

@Composable
fun WeedReaverTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography(
            bodyLarge = WrType.Body,
            bodyMedium = WrType.Body,
            bodySmall = WrType.BodyS,
            titleLarge = WrType.TitleL,
            titleMedium = WrType.TitleM,
            labelLarge = WrType.Button,
            headlineSmall = WrType.DisplayS
        ),
        shapes = Shapes(
            extraSmall = WrShape.xs,
            small = WrShape.sm,
            medium = WrShape.md,
            large = WrShape.lg,
            extraLarge = WrShape.xl
        ),
        content = content
    )
}
