package com.habeeb.transcriberecorder.recording

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.arthenica.ffmpegkit.FFmpegKit
import com.habeeb.transcriberecorder.BuildConfig
import com.habeeb.transcriberecorder.data.AppDatabase
import com.habeeb.transcriberecorder.data.TranscriptLine
import com.habeeb.transcriberecorder.network.GroqApi
import kotlinx.coroutines.delay
import java.io.File

class TranscriptionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val filePath = inputData.getString("filePath") ?: return Result.failure()
        val recordingId = inputData.getLong("recordingId", -1)
        val category = inputData.getString("category") ?: "General"
        if (recordingId == -1L) return Result.failure()

        val db = AppDatabase.getInstance(applicationContext)
        db.recordingDao().updateStatus(recordingId, "IN_PROGRESS")

        return try {
            val vocabularyHints = SettingsStore.getVocabularyHints(applicationContext)
            val chunks = chunkAudioIfNeeded(filePath)
            val allLines = mutableListOf<TranscriptLine>()
            var timeOffset = 0.0

            for (chunk in chunks) {
                val response = GroqApi.transcribeChunk(BuildConfig.GROQ_API_KEY, chunk, vocabularyHints)
                response.segments.forEach { seg ->
                    allLines.add(
                        TranscriptLine(
                            recordingId = recordingId,
                            startTime = seg.start + timeOffset,
                            endTime = seg.end + timeOffset,
                            text = seg.text.trim()
                        )
                    )
                }
                timeOffset += CHUNK_DURATION_SECONDS
                delay(1500) // stay comfortably under Groq's free-tier rate limit
            }

            db.transcriptLineDao().insertAll(allLines)
            val fullText = allLines.joinToString(" ") { it.text }
            db.recordingDao().updateTranscript(recordingId, fullText)

            // Best-effort summary; a failure here shouldn't fail the whole transcription
            try {
                val summary = GroqApi.summarize(BuildConfig.GROQ_API_KEY, fullText, category)
                db.recordingDao().updateSummary(recordingId, summary)
            } catch (e: Exception) { /* summary is optional, transcript is not */ }

            if (chunks.size > 1) chunks.forEach { it.delete() }

            Result.success()
        } catch (e: Exception) {
            db.recordingDao().updateStatus(recordingId, "FAILED")
            Result.retry()
        }
    }

    /** Splits into 10-minute chunks without re-encoding, since Groq's free tier caps file size. */
    private fun chunkAudioIfNeeded(filePath: String): List<File> {
        val inputFile = File(filePath)
        if (inputFile.length() < MAX_SINGLE_FILE_BYTES) return listOf(inputFile)

        val outputDir = inputFile.parentFile ?: return listOf(inputFile)
        val pattern = File(outputDir, "${inputFile.nameWithoutExtension}_chunk_%03d.m4a").absolutePath
        val command = "-i ${inputFile.absolutePath} -f segment -segment_time ${CHUNK_DURATION_SECONDS.toInt()} -c copy $pattern"
        FFmpegKit.execute(command)

        val chunks = outputDir.listFiles { f -> f.name.contains("_chunk_") }?.sortedBy { it.name }
        return if (chunks.isNullOrEmpty()) listOf(inputFile) else chunks
    }

    companion object {
        const val CHUNK_DURATION_SECONDS = 600.0
        const val MAX_SINGLE_FILE_BYTES = 24L * 1024 * 1024 // stay under Groq's 25MB cap
    }
}

/** Minimal SharedPreferences wrapper for the vocabulary-boost setting (professor names, jargon). */
object SettingsStore {
    private const val PREFS = "transcribe_recorder_prefs"
    private const val KEY_VOCAB = "vocabulary_hints"

    fun getVocabularyHints(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_VOCAB, "") ?: ""

    fun setVocabularyHints(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_VOCAB, value).apply()
    }
}
