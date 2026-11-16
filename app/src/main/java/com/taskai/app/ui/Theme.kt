package com.taskai.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

val Rose = Color(0xFFF282AF)
private val LightColors = lightColorScheme(
    primary = Color(0xFF9B3B63), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE5EF), onPrimaryContainer = Color(0xFF60233E),
    secondary = Color(0xFF76566A), secondaryContainer = Color(0xFFF0E5ED),
    background = Color(0xFFFCFAFC), surface = Color.White,
    surfaceVariant = Color(0xFFF3EFF3), onSurface = Color(0xFF252331),
    onSurfaceVariant = Color(0xFF6B6573), outlineVariant = Color(0xFFE8E0E7)
)
private val DarkColors = darkColorScheme(
    primary = Rose, onPrimary = Color(0xFF3D1225),
    primaryContainer = Color(0xFF542B3E), onPrimaryContainer = Color(0xFFFFDBE9),
    secondary = Color(0xFFD8BBD0), background = Color(0xFF17151C), surface = Color(0xFF211E27),
    surfaceVariant = Color(0xFF302A36), onSurface = Color(0xFFF2EDF5),
    onSurfaceVariant = Color(0xFFC9BFCE), outlineVariant = Color(0xFF48404C)
)

@Composable fun TaskAiTheme(theme: String, content: @Composable () -> Unit) {
    val dark = theme == "DARK" || (theme == "AUTO" && isSystemInDarkTheme())
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp)),
        content = content
    )
}
