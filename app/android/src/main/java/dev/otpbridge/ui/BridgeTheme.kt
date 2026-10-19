package dev.otpbridge.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable fun BridgeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = if (dark) darkColorScheme(
        primary=Color(0xFFF2F2F2), onPrimary=Color(0xFF161616), primaryContainer=Color(0xFF303030), onPrimaryContainer=Color.White,
        secondary=Color(0xFFCCCCCC), secondaryContainer=Color(0xFF303030), onSecondaryContainer=Color.White,
        background=Color(0xFF101010), onBackground=Color(0xFFF0F0F0), surface=Color(0xFF171717), onSurface=Color(0xFFF0F0F0),
        surfaceVariant=Color(0xFF252525), onSurfaceVariant=Color(0xFFBDBDBD), outline=Color(0xFF666666), outlineVariant=Color(0xFF353535),
        error=Color(0xFFE0E0E0), onError=Color.Black, errorContainer=Color(0xFF353535), onErrorContainer=Color.White
    ) else lightColorScheme(
        primary=Color(0xFF202020), onPrimary=Color.White, primaryContainer=Color(0xFFEAEAEA), onPrimaryContainer=Color(0xFF202020),
        secondary=Color(0xFF555555), secondaryContainer=Color(0xFFEAEAEA), onSecondaryContainer=Color(0xFF202020),
        background=Color(0xFFFAFAFA), onBackground=Color(0xFF202020), surface=Color.White, onSurface=Color(0xFF202020),
        surfaceVariant=Color(0xFFF0F0F0), onSurfaceVariant=Color(0xFF666666), outline=Color(0xFF888888), outlineVariant=Color(0xFFE0E0E0),
        error=Color(0xFF333333), onError=Color.White, errorContainer=Color(0xFFE8E8E8), onErrorContainer=Color(0xFF202020)
    )
    MaterialTheme(colorScheme=colors, content=content)
}
