package com.habeeb.transcriberecorder.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.habeeb.transcriberecorder.data.AppDatabase
import com.habeeb.transcriberecorder.data.Recording
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

import androidx.compose.material.icons.filled.Settings

import androidx.compose.material.icons.filled.Add
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onRecordClick: () -> Unit,
    onRecordingClick: (Long) -> Unit,
    onSettingsClick: () -> Unit,
    onImportAudio: (android.net.Uri) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var recordings by remember { mutableStateOf<List<Recording>>(emptyList()) }
    var query by remember { mutableStateOf("") }

    fun reload() {
        scope.launch {
            val dao = AppDatabase.getInstance(context).recordingDao()
            recordings = if (query.isBlank()) dao.getAll() else dao.search(query)
        }
    }

    LaunchedEffect(query) { reload() }

    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            onImportAudio(uri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recordings") },
                actions = {
                    IconButton(onClick = { audioPicker.launch("audio/*") }) {
                        Icon(Icons.Filled.Add, contentDescription = "Import Audio")
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onRecordClick, containerColor = RecordButtonColor) {
                Icon(Icons.Filled.Mic, contentDescription = "Record", tint = androidx.compose.ui.graphics.Color.White)
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search transcripts") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            )

            if (recordings.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No recordings yet. Tap the mic to start.", color = Color.Gray)
                }
            } else {
                LazyColumn {
                    items(recordings) { rec ->
                        RecordingRow(rec, onClick = { onRecordingClick(rec.id) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingRow(recording: Recording, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(recording.category, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(recording.title, style = MaterialTheme.typography.bodyLarge)
            Text(
                SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(recording.timestamp)),
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
        }
        StatusBadge(recording.transcriptionStatus)
    }
}

@Composable
private fun StatusBadge(status: String) {
    val (label, color) = when (status) {
        "DONE" -> "Transcribed" to Color(0xFF2E7D32)
        "IN_PROGRESS" -> "Transcribing…" to Color(0xFFF9A825)
        "FAILED" -> "Retry pending" to Color(0xFFC62828)
        else -> "Queued" to Color.Gray
    }
    Text(label, style = MaterialTheme.typography.labelSmall, color = color)
}
