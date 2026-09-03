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
                            onSettingsClick = { navController.navigate("settings") }
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
