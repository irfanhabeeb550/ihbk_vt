package com.habeeb.transcriberecorder.ui

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.habeeb.transcriberecorder.recording.RecorderRepository
import com.habeeb.transcriberecorder.recording.RecordingService
import kotlinx.coroutines.flow.collectLatest

private val categories = listOf("Class", "Meeting", "Interview", "General")

@Composable
fun RecordingScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var selectedCategory by remember { mutableStateOf(categories[0]) }
    var started by remember { mutableStateOf(false) }
    val isPaused by RecorderRepository.isPaused.collectAsState()
    var elapsedSeconds by remember { mutableStateOf(0) }
    val amplitudes = remember { mutableStateListOf<Float>() }
    var bookmarks by remember { mutableStateOf(listOf<Int>()) }

    LaunchedEffect(Unit) {
        RecorderRepository.amplitude.collectLatest { amp ->
            val normalized = (amp / 32767f).coerceIn(0f, 1f)
            amplitudes.add(normalized)
            if (amplitudes.size > 120) amplitudes.removeAt(0)
        }
    }

    LaunchedEffect(started, isPaused) {
        while (started && !isPaused) {
            kotlinx.coroutines.delay(1000)
            elapsedSeconds++
        }
    }

    fun startService() {
        val intent = Intent(context, RecordingService::class.java).apply {
            putExtra("category", selectedCategory)
        }
        context.startForegroundService(intent)
        started = true
    }

    fun stopService() {
        context.startService(Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
        onDone()
    }

    fun togglePause() {
        val action = if (isPaused) RecordingService.ACTION_RESUME else RecordingService.ACTION_PAUSE
        context.startService(Intent(context, RecordingService::class.java).setAction(action))
    }

    Scaffold(topBar = { TopAppBar(title = { Text(if (started) "Recording" else "New recording") }) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!started) {
                Text("Category", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    categories.forEach { cat ->
                        FilterChip(
                            selected = cat == selectedCategory,
                            onClick = { selectedCategory = cat },
                            label = { Text(cat) }
                        )
                    }
                }
                Spacer(Modifier.height(48.dp))
            }

            Text(formatTime(elapsedSeconds), style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.height(24.dp))

            Waveform(amplitudes = amplitudes, modifier = Modifier.fillMaxWidth().height(80.dp))

            Spacer(Modifier.height(16.dp))
            if (bookmarks.isNotEmpty()) {
                Text("${bookmarks.size} bookmark(s): ${bookmarks.joinToString { formatTime(it) }}", style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.weight(1f))

            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                if (started) {
                    OutlinedIconButton(onClick = { bookmarks = bookmarks + elapsedSeconds }) {
                        Icon(Icons.Filled.BookmarkAdd, contentDescription = "Bookmark")
                    }
                    OutlinedIconButton(onClick = { togglePause() }) {
                        Icon(if (isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause, contentDescription = "Pause/Resume")
                    }
                }
                FloatingActionButton(
                    onClick = { if (started) stopService() else startService() },
                    containerColor = RecordButtonColor,
                    modifier = Modifier.size(72.dp)
                ) {
                    Icon(
                        if (started) Icons.Filled.Stop else Icons.Filled.Mic,
                        contentDescription = if (started) "Stop" else "Record",
                        tint = androidx.compose.ui.graphics.Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun Waveform(amplitudes: List<Float>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (amplitudes.isEmpty()) return@Canvas
        val barWidth = size.width / 120f
        val midY = size.height / 2f
        amplitudes.forEachIndexed { index, amp ->
            val x = index * barWidth
            val barHeight = amp * midY
            drawLine(
                color = WaveformColor,
                start = Offset(x, midY - barHeight),
                end = Offset(x, midY + barHeight),
                strokeWidth = barWidth * 0.6f,
                cap = StrokeCap.Round
            )
        }
    }
}

private fun formatTime(totalSeconds: Int): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "%02d:%02d".format(m, s)
}
