package com.habeeb.transcriberecorder.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SamsungBlue = Color(0xFF1565C0)
private val RecordRed = Color(0xFFE53935)

private val colorScheme = lightColorScheme(
    primary = SamsungBlue,
    secondary = RecordRed,
    background = Color(0xFFF5F5F5),
    surface = Color.White
)

@Composable
fun TranscribeRecorderTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colorScheme, content = content)
}

val WaveformColor = SamsungBlue
val RecordButtonColor = RecordRed
