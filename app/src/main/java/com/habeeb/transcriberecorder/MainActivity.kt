package com.habeeb.transcriberecorder

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.habeeb.transcriberecorder.ui.DetailScreen
import com.habeeb.transcriberecorder.ui.HomeScreen
import com.habeeb.transcriberecorder.ui.RecordingScreen
import com.habeeb.transcriberecorder.ui.TranscribeRecorderTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

class MainActivity : ComponentActivity() {

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* handled by the OS dialog; recording just won't start without RECORD_AUDIO granted */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissions.launch(perms.toTypedArray())

        setContent {
            TranscribeRecorderTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            onRecordClick = { navController.navigate("record") },
                            onRecordingClick = { id -> navController.navigate("detail/$id") },
                            onSettingsClick = { navController.navigate("settings") },
                            onImportAudio = { uri ->
                                lifecycleScope.launch(Dispatchers.IO) {
                                    try {
                                        val dir = java.io.File(filesDir, "recordings").apply { mkdirs() }
                                        val fileName = "import_${System.currentTimeMillis()}.m4a"
                                        val outputFile = java.io.File(dir, fileName)
                                        
                                        contentResolver.openInputStream(uri)?.use { input ->
                                            outputFile.outputStream().use { output ->
                                                input.copyTo(output)
                                            }
                                        }
                                        
                                        var title = "Imported Audio"
                                        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                                            if (cursor.moveToFirst()) {
                                                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                                if (nameIndex != -1) title = cursor.getString(nameIndex)
                                            }
                                        }
                                        
                                        val db = com.habeeb.transcriberecorder.data.AppDatabase.getInstance(applicationContext)
                                        val recordingId = db.recordingDao().insert(
                                            com.habeeb.transcriberecorder.data.Recording(
                                                title = title,
                                                filePath = outputFile.absolutePath,
                                                timestamp = System.currentTimeMillis(),
                                                duration = 0,
                                                category = "Imported"
                                            )
                                        )
                                        
                                        val workRequest = androidx.work.OneTimeWorkRequestBuilder<com.habeeb.transcriberecorder.recording.TranscriptionWorker>()
                                            .setInputData(
                                                androidx.work.workDataOf(
                                                    "filePath" to outputFile.absolutePath,
                                                    "recordingId" to recordingId,
                                                    "category" to "Imported"
                                                )
                                            )
                                            .setConstraints(
                                                androidx.work.Constraints.Builder()
                                                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                                                    .build()
                                            )
                                            .build()
                                        androidx.work.WorkManager.getInstance(applicationContext).enqueue(workRequest)
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }
                            }
                        )
                    }
                    composable("record") {
                        RecordingScreen(onDone = { navController.popBackStack() })
                    }
                    composable("detail/{id}") { backStackEntry ->
                        val id = backStackEntry.arguments?.getString("id")?.toLongOrNull() ?: return@composable
                        DetailScreen(recordingId = id, onBack = { navController.popBackStack() })
                    }
                    composable("settings") {
                        com.habeeb.transcriberecorder.ui.SettingsScreen(
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }
}
