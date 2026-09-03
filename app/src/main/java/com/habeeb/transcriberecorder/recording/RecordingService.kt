package com.habeeb.transcriberecorder.recording

import android.app.*
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.habeeb.transcriberecorder.R
import com.habeeb.transcriberecorder.data.AppDatabase
import com.habeeb.transcriberecorder.data.Recording
import kotlinx.coroutines.*
import java.io.File

class RecordingService : Service() {

    private var mediaRecorder: MediaRecorder? = null
    private var currentFilePath: String = ""
    private var currentRecordingId: Long = -1
    private var category: String = "General"
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private val channelId = "recording_channel"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        category = intent?.getStringExtra("category") ?: "General"
        when (intent?.action) {
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            else -> {
                startForeground(NOTIF_ID, buildNotification())
                startRecording()
            }
        }
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Recording", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Recording $category")
            .setContentText("Tap to return to the recorder")
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .build()
    }

    private fun startRecording() {
        val dir = File(filesDir, "recordings").apply { mkdirs() }
        val fileName = "rec_${System.currentTimeMillis()}.m4a"
        val outputFile = File(dir, fileName)
        currentFilePath = outputFile.absolutePath

        mediaRecorder = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128000)
            setAudioSamplingRate(44100)
            setOutputFile(currentFilePath)
            prepare()
            start()
        }
        RecorderRepository.setRecording(true)
        RecorderRepository.setPaused(false)

        serviceScope.launch {
            val dao = AppDatabase.getInstance(applicationContext).recordingDao()
            currentRecordingId = dao.insert(
                Recording(
                    title = "$category - ${System.currentTimeMillis()}",
                    filePath = currentFilePath,
                    timestamp = System.currentTimeMillis(),
                    duration = 0,
                    category = category
                )
            )
        }

        serviceScope.launch {
            while (isActive) {
                if (RecorderRepository.isPaused.value == false) {
                    val amp = try { mediaRecorder?.maxAmplitude ?: 0 } catch (e: Exception) { 0 }
                    RecorderRepository.updateAmplitude(amp)
                }
                delay(100) // 10fps is plenty for a scrolling waveform
            }
        }
    }

    private fun pauseRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            mediaRecorder?.pause()
            RecorderRepository.setPaused(true)
        }
    }

    private fun resumeRecording() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            mediaRecorder?.resume()
            RecorderRepository.setPaused(false)
        }
    }

    override fun onDestroy() {
        val filePath = currentFilePath
        val recordingId = currentRecordingId

        try {
            mediaRecorder?.apply { stop(); release() }
        } catch (e: Exception) {
            // stop() throws if called too soon after start(); nothing useful to recover here
        }
        mediaRecorder = null
        RecorderRepository.setRecording(false)

        if (recordingId != -1L) {
            val workRequest = OneTimeWorkRequestBuilder<TranscriptionWorker>()
                .setInputData(
                    workDataOf(
                        "filePath" to filePath,
                        "recordingId" to recordingId,
                        "category" to category
                    )
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(applicationContext).enqueue(workRequest)
        }

        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIF_ID = 1
        const val ACTION_PAUSE = "PAUSE"
        const val ACTION_RESUME = "RESUME"
        const val ACTION_STOP = "STOP"
    }
}
