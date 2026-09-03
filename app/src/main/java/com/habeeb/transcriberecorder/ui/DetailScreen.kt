package com.habeeb.transcriberecorder.ui

import android.media.MediaPlayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.habeeb.transcriberecorder.data.AppDatabase
import com.habeeb.transcriberecorder.data.Recording
import com.habeeb.transcriberecorder.data.TranscriptLine
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(recordingId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var recording by remember { mutableStateOf<Recording?>(null) }
    var lines by remember { mutableStateOf<List<TranscriptLine>>(emptyList()) }
    var tab by remember { mutableStateOf(0) }
    val mediaPlayer = remember { MediaPlayer() }
    var isPlaying by remember { mutableStateOf(false) }

    LaunchedEffect(recordingId) {
        val db = AppDatabase.getInstance(context)
        recording = db.recordingDao().getById(recordingId)
        lines = db.transcriptLineDao().getForRecording(recordingId)
        recording?.filePath?.let { path ->
            mediaPlayer.reset()
            mediaPlayer.setDataSource(path)
            mediaPlayer.prepare()
        }
    }

    DisposableEffect(Unit) { onDispose { mediaPlayer.release() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(recording?.title ?: "Recording") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
                    IconButton(onClick = {
                        val textToCopy = if (tab == 0) {
                            lines.joinToString("\n") { "[${formatTimestamp(it.startTime)}] ${it.text}" }
                        } else {
                            recording?.summary ?: ""
                        }
                        clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(textToCopy))
                    }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = "Copy")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                IconButton(onClick = {
                    if (isPlaying) mediaPlayer.pause() else mediaPlayer.start()
                    isPlaying = !isPlaying
                }) {
                    Icon(if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = "Play/Pause", modifier = Modifier.size(48.dp))
                }
            }

            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Transcript") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Summary") })
            }

            when (tab) {
                0 -> TranscriptTab(lines, status = recording?.transcriptionStatus ?: "PENDING") { line ->
                    mediaPlayer.seekTo((line.startTime * 1000).toInt())
                    mediaPlayer.start()
                    isPlaying = true
                }
                1 -> SummaryTab(recording?.summary)
            }
        }
    }
}

@Composable
private fun TranscriptTab(lines: List<TranscriptLine>, status: String, onLineClick: (TranscriptLine) -> Unit) {
    if (lines.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
            Text(
                when (status) {
                    "IN_PROGRESS" -> "Transcribing… this runs in the background, check back shortly."
                    "FAILED" -> "Transcription hit a snag and will retry automatically."
                    else -> "Waiting to transcribe."
                }
            )
        }
        return
    }
    LazyColumn(Modifier.padding(horizontal = 16.dp)) {
        items(lines) { line ->
            Column(
                Modifier.fillMaxWidth().clickable { onLineClick(line) }.padding(vertical = 8.dp)
            ) {
                Text(formatTimestamp(line.startTime), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(line.text, style = MaterialTheme.typography.bodyLarge)
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun SummaryTab(summary: String?) {
    Box(Modifier.fillMaxSize().padding(16.dp)) {
        Text(summary ?: "Summary will appear here once the transcript is ready.")
    }
}

private fun formatTimestamp(seconds: Double): String {
    val totalSeconds = seconds.toInt()
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "%02d:%02d".format(m, s)
}
