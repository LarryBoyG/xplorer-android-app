package com.example.xirolite

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

object XiroDesignTokens {
    val BackgroundTop = Color(0xFF23272F)
    val BackgroundMid = Color(0xFF1B1F27)
    val BackgroundBottom = Color(0xFF151921)

    val SurfaceTop = Color(0xFF484D57)
    val Surface = Color(0xFF343A45)
    val SurfaceBottom = Color(0xFF272C35)
    val SurfaceInset = Color(0xFF222730)
    val SurfaceOverlay = Color(0xCC1B1F26)

    val Accent = Color(0xFF75E52E)
    val AccentBright = Color(0xFF91FF58)
    val AccentMuted = Color(0xFFB8F39A)

    val TextPrimary = Color(0xFFF8FBFF)
    val TextSecondary = Color(0xFFD3DAE5)
    val TextMuted = Color(0xFFA5AFBD)

    val BorderLight = Color(0x33FFFFFF)
    val BorderStrong = Color(0x55FFFFFF)
    val ShadowDark = Color(0xCC0E1117)
    val ShadowSoft = Color(0x40171B22)
}

fun xiroColorScheme() = darkColorScheme(
    primary = XiroDesignTokens.Accent,
    secondary = XiroDesignTokens.AccentBright,
    tertiary = XiroDesignTokens.AccentMuted,
    background = XiroDesignTokens.BackgroundMid,
    surface = XiroDesignTokens.Surface,
    surfaceVariant = XiroDesignTokens.SurfaceInset,
    onPrimary = XiroDesignTokens.TextPrimary,
    onSecondary = XiroDesignTokens.TextPrimary,
    onTertiary = XiroDesignTokens.TextPrimary,
    onBackground = XiroDesignTokens.TextPrimary,
    onSurface = XiroDesignTokens.TextPrimary,
    onSurfaceVariant = XiroDesignTokens.TextSecondary,
    outline = XiroDesignTokens.BorderLight
)

fun xiroMainBackgroundBrush(): Brush = Brush.verticalGradient(
    colors = listOf(
        XiroDesignTokens.BackgroundTop,
        XiroDesignTokens.BackgroundMid,
        XiroDesignTokens.BackgroundBottom
    )
)

private fun blend(base: Color, toward: Color, amount: Float): Color {
    val t = amount.coerceIn(0f, 1f)
    return Color(
        red = base.red + ((toward.red - base.red) * t),
        green = base.green + ((toward.green - base.green) * t),
        blue = base.blue + ((toward.blue - base.blue) * t),
        alpha = base.alpha + ((toward.alpha - base.alpha) * t)
    )
}

private fun raisedBrush(base: Color): Brush = Brush.verticalGradient(
    colors = listOf(
        blend(base, Color.White, 0.12f),
        base,
        blend(base, Color.Black, 0.16f)
    )
)

private fun accentBrush(): Brush = Brush.verticalGradient(
    colors = listOf(
        blend(XiroDesignTokens.AccentBright, Color.White, 0.12f),
        XiroDesignTokens.Accent,
        blend(XiroDesignTokens.Accent, Color.Black, 0.18f)
    )
)

@Composable
fun XiroRaisedCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    containerColor: Color = XiroDesignTokens.Surface,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier
            .shadow(
                elevation = 16.dp,
                shape = shape,
                ambientColor = XiroDesignTokens.ShadowSoft,
                spotColor = XiroDesignTokens.ShadowDark
            ),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        border = BorderStroke(1.dp, XiroDesignTokens.BorderLight)
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(raisedBrush(containerColor))
                .padding(contentPadding)
        ) {
            content()
        }
    }
}

@Composable
fun xiroOutlinedTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = XiroDesignTokens.TextPrimary,
    unfocusedTextColor = XiroDesignTokens.TextPrimary,
    disabledTextColor = XiroDesignTokens.TextMuted,
    focusedLabelColor = XiroDesignTokens.AccentBright,
    unfocusedLabelColor = XiroDesignTokens.TextMuted,
    disabledLabelColor = XiroDesignTokens.TextMuted,
    cursorColor = XiroDesignTokens.AccentBright,
    focusedBorderColor = XiroDesignTokens.AccentBright,
    unfocusedBorderColor = XiroDesignTokens.BorderStrong,
    disabledBorderColor = XiroDesignTokens.BorderLight,
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    disabledContainerColor = Color.Transparent,
    focusedPlaceholderColor = XiroDesignTokens.TextMuted,
    unfocusedPlaceholderColor = XiroDesignTokens.TextMuted,
    disabledPlaceholderColor = XiroDesignTokens.TextMuted
)

@Composable
fun XiroPrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(20.dp),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.shadow(
            elevation = 10.dp,
            shape = shape,
            ambientColor = XiroDesignTokens.ShadowSoft,
            spotColor = XiroDesignTokens.ShadowDark
        ),
        shape = shape,
        contentPadding = contentPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = XiroDesignTokens.TextPrimary,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = XiroDesignTokens.TextMuted
        ),
        border = BorderStroke(1.dp, blend(XiroDesignTokens.AccentBright, Color.White, 0.2f))
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .clip(shape)
                .background(if (enabled) accentBrush() else raisedBrush(XiroDesignTokens.SurfaceBottom))
                .border(1.dp, XiroDesignTokens.BorderStrong, shape)
                .padding(horizontal = 2.dp, vertical = 1.dp)
        ) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.padding(contentPadding),
                content = content
            )
        }
    }
}

@Composable
fun XiroSecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(20.dp),
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.shadow(
            elevation = 8.dp,
            shape = shape,
            ambientColor = XiroDesignTokens.ShadowSoft,
            spotColor = XiroDesignTokens.ShadowDark
        ),
        shape = shape,
        contentPadding = contentPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = XiroDesignTokens.TextPrimary,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = XiroDesignTokens.TextMuted
        ),
        border = BorderStroke(1.dp, XiroDesignTokens.BorderLight)
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .clip(shape)
                .background(raisedBrush(if (enabled) XiroDesignTokens.Surface else XiroDesignTokens.SurfaceBottom))
                .padding(horizontal = 2.dp, vertical = 1.dp)
        ) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.padding(contentPadding),
                content = content
            )
        }
    }
}

@Composable
fun XiroToggleChip(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable () -> Unit
) {
    XiroPillSurface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        containerColor = if (selected) XiroDesignTokens.Accent else XiroDesignTokens.SurfaceInset
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
        ) {
            label()
        }
    }
}

@Composable
fun XiroPillSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    containerColor: Color = XiroDesignTokens.SurfaceInset,
    content: @Composable () -> Unit
) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .shadow(
                elevation = 8.dp,
                shape = shape,
                ambientColor = XiroDesignTokens.ShadowSoft,
                spotColor = XiroDesignTokens.ShadowDark
            )
            .clip(shape)
            .background(raisedBrush(containerColor))
            .border(1.dp, XiroDesignTokens.BorderLight, shape)
    ) {
        content()
    }
}

@Composable
fun XiroIconSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(18.dp),
    content: @Composable () -> Unit
) {
    XiroPillSurface(
        modifier = modifier,
        shape = shape,
        containerColor = XiroDesignTokens.SurfaceOverlay,
        content = content
    )
}

@Composable
fun XiroDialogPanel(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    content: @Composable () -> Unit
) {
    XiroRaisedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(30.dp),
        containerColor = XiroDesignTokens.Surface,
        contentPadding = contentPadding,
        content = content
    )
}
