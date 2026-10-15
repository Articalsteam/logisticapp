package com.articals.logistics.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The design system.
 *
 * One brand colour, one type scale, one spacing scale, light and dark. There is no
 * dynamic colour: a dispatcher's phone choosing a different palette from a rider's
 * would make support conversations harder than the personalisation is worth, and the
 * two platforms must look identical for the same reason.
 *
 * Typography is Material 3's scale with the brand's shape on it, and scaling is done
 * by the caller (see [ArticalsTheme]) so a large-font device and the demo control
 * panel use exactly the same mechanism.
 */

// --- Brand -----------------------------------------------------------------

/** Deep green: the colour on the pilot's invoices, and readable on a cheap screen. */
private val BrandGreen = Color(0xFF0F5C46)
private val BrandGreenDark = Color(0xFF0A4233)
private val BrandGreenLight = Color(0xFF9ED4C1)
private val AccentSand = Color(0xFF8A5A22)
private val AccentSandLight = Color(0xFFE8C79A)

private val Success = Color(0xFF1B7A45)
private val Warning = Color(0xFF9A6A00)
private val Danger = Color(0xFFA5281F)

private val LightColors = lightColorScheme(
    primary = BrandGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCBEADF),
    onPrimaryContainer = Color(0xFF06291F),
    secondary = AccentSand,
    onSecondary = Color.White,
    secondaryContainer = AccentSandLight,
    onSecondaryContainer = Color(0xFF2C1B04),
    tertiary = Color(0xFF39597A),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCFE1F5),
    onTertiaryContainer = Color(0xFF102538),
    error = Danger,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD5),
    onErrorContainer = Color(0xFF410E06),
    background = Color(0xFFF7F8F8),
    onBackground = Color(0xFF161A19),
    surface = Color(0xFFF7F8F8),
    onSurface = Color(0xFF161A19),
    surfaceVariant = Color(0xFFE3E7E5),
    onSurfaceVariant = Color(0xFF44504B),
    outline = Color(0xFF74807A),
    outlineVariant = Color(0xFFC4CCC8),
)

private val DarkColors = darkColorScheme(
    primary = BrandGreenLight,
    onPrimary = Color(0xFF06301F),
    primaryContainer = BrandGreenDark,
    onPrimaryContainer = Color(0xFFCBEADF),
    secondary = AccentSandLight,
    onSecondary = Color(0xFF3A2400),
    secondaryContainer = Color(0xFF5C3A10),
    onSecondaryContainer = AccentSandLight,
    tertiary = Color(0xFFAEC9E6),
    onTertiary = Color(0xFF102538),
    tertiaryContainer = Color(0xFF28425F),
    onTertiaryContainer = Color(0xFFCFE1F5),
    error = Color(0xFFFFB4A9),
    onError = Color(0xFF690B02),
    errorContainer = Color(0xFF8C1D12),
    onErrorContainer = Color(0xFFFFDAD5),
    background = Color(0xFF101314),
    onBackground = Color(0xFFE1E3E2),
    surface = Color(0xFF101314),
    onSurface = Color(0xFFE1E3E1),
    surfaceVariant = Color(0xFF3F4945),
    onSurfaceVariant = Color(0xFFBFC9C4),
    outline = Color(0xFF89938E),
    outlineVariant = Color(0xFF3F4945),
)

/** Status colours, shared by every screen that shows a delivery state. */
object ArticalsStatusColors {
    val success: Color get() = Success
    val warning: Color get() = Warning
    val danger: Color get() = Danger
    val neutral: Color get() = Color(0xFF5A6360)
}

/** The 4dp grid. Every screen uses these instead of raw numbers. */
object Spacing {
    val hairline = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    /** Standard screen padding. */
    val screen = 16.dp

    /** Minimum touch target — riders work one-handed, often in sunlight. */
    val touchTarget = 48.dp
}

private val ArticalsTypography = Typography().let { base ->
    base.copy(
        displaySmall = base.displaySmall.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium),
        // Amounts are read digit by digit; a monospace figure column stops them
        // dancing between rows in a list.
        bodyLarge = base.bodyLarge.copy(lineHeight = 24.sp),
    )
}

private val ArticalsShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * The app's theme.
 *
 * [darkMode] is null by default, meaning "follow the device"; the demo control panel
 * pins it to true or false so both looks can be shown in a single demonstration.
 *
 * [fontScale] multiplies the *device's* font scale rather than replacing it, so a
 * user who has already enlarged text on their phone keeps that, and the demo panel
 * can add to it. It is clamped to a range that keeps buttons usable.
 */
@Composable
fun ArticalsTheme(
    darkMode: Boolean? = null,
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val isDark = darkMode ?: isSystemInDarkTheme()
    val colors = if (isDark) DarkColors else LightColors

    val density = LocalDensity.current
    val effectiveScale = (density.fontScale * fontScale).coerceIn(0.8f, 1.8f)

    CompositionLocalProvider(
        LocalDensity provides Density(density = density.density, fontScale = effectiveScale),
    ) {
        MaterialTheme(
            colorScheme = colors,
            typography = ArticalsTypography,
            shapes = ArticalsShapes,
            content = content,
        )
    }
}
