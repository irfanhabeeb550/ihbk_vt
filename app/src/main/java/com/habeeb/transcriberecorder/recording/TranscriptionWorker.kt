package com.habeeb.transcriberecorder.recording

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.habeeb.transcriberecorder.BuildConfig
import com.habeeb.transcriberecorder.R
import com.habeeb.transcriberecorder.data.AppDatabase
import com.habeeb.transcriberecorder.data.TranscriptLine
import com.habeeb.transcriberecorder.network.GroqApi
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.nio.ByteBuffer

private const val TAG = "TranscriptionWorker"
private const val NOTIF_CHANNEL = "transcription_channel"
private const val NOTIF_ID = 2

class TranscriptionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val filePath = inputData.getString("filePath") ?: return Result.failure()
        val recordingId = inputData.getLong("recordingId", -1)
        val category = inputData.getString("category") ?: "General"
        if (recordingId == -1L) return Result.failure()

        // Run as a foreground service so Android doesn't kill us during long transcriptions
        setForeground(createForegroundInfo("Preparing transcription…"))

        val db = AppDatabase.getInstance(applicationContext)
        db.recordingDao().updateStatus(recordingId, "IN_PROGRESS")

        return try {
            val vocabularyHints = SettingsStore.getVocabularyHints(applicationContext)
            val chunks = chunkAudioIfNeeded(filePath)
            val totalChunks = chunks.size
            Log.d(TAG, "Split into $totalChunks chunk(s)")

            setForeground(createForegroundInfo("Transcribing 0/$totalChunks chunks…"))

            val results = mutableListOf<IndexedResult>()
            for ((index, chunk) in chunks.withIndex()) {
                Log.d(TAG, "Transcribing chunk $index (${chunk.length() / 1024}KB)")
                var lastException: Exception? = null
                
                // Retry each chunk up to 4 times with exponential backoff for 429 Too Many Requests
                for (attempt in 1..4) {
                    try {
                        setForeground(createForegroundInfo("Transcribing chunk ${index + 1}/$totalChunks…"))
                        val response = GroqApi.transcribeChunk(
                            BuildConfig.GROQ_API_KEY, chunk, vocabularyHints
                        )
                        Log.d(TAG, "Chunk $index done: ${response.segments.size} segments")
                        results.add(IndexedResult(index, response.segments))
                        
                        // Stay under Groq's 20 requests per minute limit (approx 1 request every 3 seconds)
                        if (index < chunks.size - 1) delay(3500) 
                        lastException = null
                        break
                    } catch (e: Exception) {
                        lastException = e
                        val msg = e.message ?: ""
                        Log.w(TAG, "Chunk $index attempt $attempt failed: $msg")
                        
                        // If it's a rate limit (429), wait much longer
                        if (msg.contains("429") || msg.contains("Too Many Requests")) {
                            Log.w(TAG, "Hit rate limit, backing off for 30 seconds...")
                            delay(30_000L)
                        } else if (attempt < 4) {
                            delay(5000L * attempt)
                        }
                    }
                }
                
                if (lastException != null) {
                    throw lastException
                }
            }

            setForeground(createForegroundInfo("Stitching transcript…"))

            // Stitch results back in order
            val allLines = mutableListOf<TranscriptLine>()
            results.forEach { result ->
                val timeOffset = result.chunkIndex * CHUNK_DURATION_SECONDS
                result.segments.forEach { seg ->
                    allLines.add(
                        TranscriptLine(
                            recordingId = recordingId,
                            startTime = seg.start + timeOffset,
                            endTime = seg.end + timeOffset,
                            text = seg.text.trim()
                        )
                    )
                }
            }

            db.transcriptLineDao().insertAll(allLines)
            val fullText = allLines.joinToString(" ") { it.text }
            db.recordingDao().updateTranscript(recordingId, fullText)

            // Best-effort summary
            setForeground(createForegroundInfo("Generating summary…"))
            try {
                val summary = GroqApi.summarize(BuildConfig.GROQ_API_KEY, fullText, category)
                db.recordingDao().updateSummary(recordingId, summary)
            } catch (e: Exception) {
                Log.w(TAG, "Summary failed (non-fatal): ${e.message}")
            }

            if (chunks.size > 1) chunks.forEach { it.delete() }
            Result.success()
        } catch (e: Exception) {
            val msg = e.message ?: "Unknown error"
            Log.e(TAG, "Transcription failed: $msg", e)
            
            // If it's a hard error (like file too large), don't retry.
            if (msg.contains("too large") || runAttemptCount > 3) {
                db.recordingDao().updateStatus(recordingId, "FAILED: $msg")
                return Result.failure()
            } else {
                db.recordingDao().updateStatus(recordingId, "FAILED (Retrying...)")
                return Result.retry()
            }
        }
    }

    private fun createForegroundInfo(progress: String): ForegroundInfo {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL, "Transcription", NotificationManager.IMPORTANCE_LOW
            )
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(applicationContext, NOTIF_CHANNEL)
            .setContentTitle("Transcribe Recorder")
            .setContentText(progress)
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return ForegroundInfo(NOTIF_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        }
        return ForegroundInfo(NOTIF_ID, notification)
    }

    private data class IndexedResult(
        val chunkIndex: Int,
        val segments: List<com.habeeb.transcriberecorder.network.GroqSegment>
    )

    /**
     * Splits into ~5-minute chunks using Android's MediaExtractor/MediaMuxer.
     * Shorter chunks = smaller files = faster uploads, especially on mobile data.
     */
    private fun chunkAudioIfNeeded(filePath: String): List<File> {
        val inputFile = File(filePath)
        if (inputFile.length() < MAX_SINGLE_FILE_BYTES) {
            Log.d(TAG, "File ${inputFile.length() / 1024}KB is under threshold, no chunking needed")
            return listOf(inputFile)
        }

        val outputDir = File(applicationContext.cacheDir, "chunks_${System.currentTimeMillis()}").apply { mkdirs() }
        val chunks = mutableListOf<File>()
        val chunkDurationUs = (CHUNK_DURATION_SECONDS * 1_000_000).toLong()

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(filePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: run {
                Log.w(TAG, "No audio track found, sending full file")
                return listOf(inputFile)
            }

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            Log.d(TAG, "Audio format: $format")

            var chunkIndex = 0
            var chunkStartUs = 0L
            var done = false

            while (!done) {
                val chunkFile = File(outputDir, "chunk_${String.format("%03d", chunkIndex)}.m4a")
                val muxer = MediaMuxer(chunkFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val muxerTrack = muxer.addTrack(format)
                muxer.start()

                val buffer = ByteBuffer.allocate(1024 * 1024)
                val bufferInfo = android.media.MediaCodec.BufferInfo()
                var samplesInChunk = 0

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
                    samplesInChunk++
                }

                muxer.stop()
                muxer.release()

                if (samplesInChunk > 0) {
                    chunks.add(chunkFile)
                    Log.d(TAG, "Created chunk $chunkIndex: ${chunkFile.length() / 1024}KB, $samplesInChunk samples")
                } else {
                    chunkFile.delete()
                }

                chunkIndex++
                chunkStartUs += chunkDurationUs
            }
        } catch (e: Exception) {
            Log.e(TAG, "Chunking failed: ${e.message}", e)
            // Clean up any partial chunks
            chunks.forEach { it.delete() }
            outputDir.delete()
            if (inputFile.length() > MAX_SINGLE_FILE_BYTES) {
                throw Exception("File is too large (${inputFile.length() / 1024 / 1024}MB) and format cannot be chunked. Please use M4A or keep under 20MB.")
            }
            return listOf(inputFile)
        } finally {
            extractor.release()
        }

        return if (chunks.isEmpty()) listOf(inputFile) else chunks
    }

    companion object {
        const val CHUNK_DURATION_SECONDS = 300.0   // 5-minute chunks
        const val MAX_SINGLE_FILE_BYTES = 20L * 1024 * 1024 // 20MB threshold
    }
}

/** Minimal SharedPreferences wrapper for the vocabulary-boost setting. */
object SettingsStore {
    private const val PREFS = "transcribe_recorder_prefs"
    private const val KEY_VOCAB = "vocabulary_hints"

    fun getVocabularyHints(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_VOCAB, "") ?: ""

    fun setVocabularyHints(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_VOCAB, value).apply()
    }
}
