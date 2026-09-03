package com.habeeb.transcriberecorder.recording

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaMuxer
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.habeeb.transcriberecorder.BuildConfig
import com.habeeb.transcriberecorder.data.AppDatabase
import com.habeeb.transcriberecorder.data.TranscriptLine
import com.habeeb.transcriberecorder.network.GroqApi
import kotlinx.coroutines.delay
import java.io.File
import java.nio.ByteBuffer

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

    /**
     * Splits into 10-minute chunks using Android's MediaExtractor/MediaMuxer,
     * since Groq's free tier caps file size at 25MB.
     */
    private fun chunkAudioIfNeeded(filePath: String): List<File> {
        val inputFile = File(filePath)
        if (inputFile.length() < MAX_SINGLE_FILE_BYTES) return listOf(inputFile)

        val outputDir = inputFile.parentFile ?: return listOf(inputFile)
        val chunks = mutableListOf<File>()
        val chunkDurationUs = (CHUNK_DURATION_SECONDS * 1_000_000).toLong()

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(filePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return listOf(inputFile)

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)

            var chunkIndex = 0
            var chunkStartUs = 0L
            var done = false

            while (!done) {
                val chunkFile = File(outputDir, "${inputFile.nameWithoutExtension}_chunk_${String.format("%03d", chunkIndex)}.m4a")
                val muxer = MediaMuxer(chunkFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val muxerTrack = muxer.addTrack(format)
                muxer.start()

                val buffer = ByteBuffer.allocate(1024 * 1024)
                val bufferInfo = android.media.MediaCodec.BufferInfo()

                while (true) {
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) {
                        done = true
                        break
                    }
                    val sampleTime = extractor.sampleTime
                    if (sampleTime >= chunkStartUs + chunkDurationUs) {
                        break
                    }

                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = sampleTime - chunkStartUs
                    bufferInfo.flags = extractor.sampleFlags
                    muxer.writeSampleData(muxerTrack, buffer, bufferInfo)
                    extractor.advance()
                }

                muxer.stop()
                muxer.release()
                chunks.add(chunkFile)
                chunkIndex++
                chunkStartUs += chunkDurationUs
            }
        } catch (e: Exception) {
            return listOf(inputFile) // fall back to un-chunked
        } finally {
            extractor.release()
        }

        return if (chunks.isEmpty()) listOf(inputFile) else chunks
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
