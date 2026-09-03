package com.habeeb.transcriberecorder.recording

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.habeeb.transcriberecorder.BuildConfig
import com.habeeb.transcriberecorder.data.AppDatabase
import com.habeeb.transcriberecorder.data.TranscriptLine
import com.habeeb.transcriberecorder.network.GroqApi
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.nio.ByteBuffer

private const val TAG = "TranscriptionWorker"

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
            Log.d(TAG, "Split into ${chunks.size} chunk(s)")

            // Process chunks in parallel (3 at a time) for speed
            val semaphore = Semaphore(3)
            val results = coroutineScope {
                chunks.mapIndexed { index, chunk ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            Log.d(TAG, "Transcribing chunk $index (${chunk.length() / 1024}KB)")
                            var lastException: Exception? = null
                            // Retry each chunk up to 3 times
                            for (attempt in 1..3) {
                                try {
                                    val response = GroqApi.transcribeChunk(
                                        BuildConfig.GROQ_API_KEY, chunk, vocabularyHints
                                    )
                                    Log.d(TAG, "Chunk $index done: ${response.segments.size} segments")
                                    return@async IndexedResult(index, response.segments)
                                } catch (e: Exception) {
                                    lastException = e
                                    Log.w(TAG, "Chunk $index attempt $attempt failed: ${e.message}")
                                    if (attempt < 3) delay(2000L * attempt)
                                }
                            }
                            throw lastException ?: Exception("Unknown error on chunk $index")
                        }
                    }
                }.awaitAll()
            }

            // Stitch results back in order
            val allLines = mutableListOf<TranscriptLine>()
            results.sortedBy { it.chunkIndex }.forEach { result ->
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
            try {
                val summary = GroqApi.summarize(BuildConfig.GROQ_API_KEY, fullText, category)
                db.recordingDao().updateSummary(recordingId, summary)
            } catch (e: Exception) {
                Log.w(TAG, "Summary failed (non-fatal): ${e.message}")
            }

            if (chunks.size > 1) chunks.forEach { it.delete() }
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Transcription failed: ${e.message}", e)
            db.recordingDao().updateStatus(recordingId, "FAILED")
            Result.retry()
        }
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
        if (inputFile.length() < MAX_SINGLE_FILE_BYTES) return listOf(inputFile)

        val outputDir = File(applicationContext.cacheDir, "chunks").apply { mkdirs() }
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

            var chunkIndex = 0
            var chunkStartUs = 0L
            var done = false

            while (!done) {
                val chunkFile = File(outputDir, "chunk_${System.currentTimeMillis()}_${String.format("%03d", chunkIndex)}.m4a")
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
                    Log.d(TAG, "Created chunk $chunkIndex: ${chunkFile.length() / 1024}KB")
                } else {
                    chunkFile.delete()
                }

                chunkIndex++
                chunkStartUs += chunkDurationUs
            }
        } catch (e: Exception) {
            Log.e(TAG, "Chunking failed, sending full file: ${e.message}", e)
            // Clean up any partial chunks
            chunks.forEach { it.delete() }
            return listOf(inputFile)
        } finally {
            extractor.release()
        }

        return if (chunks.isEmpty()) listOf(inputFile) else chunks
    }

    companion object {
        const val CHUNK_DURATION_SECONDS = 300.0   // 5-minute chunks for faster parallel uploads
        const val MAX_SINGLE_FILE_BYTES = 20L * 1024 * 1024 // 20MB threshold (safe margin under 25MB)
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
