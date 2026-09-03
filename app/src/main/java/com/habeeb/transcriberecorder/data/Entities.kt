package com.habeeb.transcriberecorder.data

import androidx.room.*

@Entity(tableName = "recordings")
data class Recording(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val filePath: String,
    val timestamp: Long,
    val duration: Long,
    val category: String,          // Class, Meeting, Interview, General
    val transcript: String? = null,
    val summary: String? = null,
    val transcriptionStatus: String = "PENDING" // PENDING, IN_PROGRESS, DONE, FAILED
)

@Entity(
    tableName = "transcript_lines",
    foreignKeys = [ForeignKey(
        entity = Recording::class,
        parentColumns = ["id"],
        childColumns = ["recordingId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("recordingId")]
)
data class TranscriptLine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val recordingId: Long,
    val startTime: Double,
    val endTime: Double,
    val text: String,
    val speakerLabel: String? = null
)

@Dao
interface RecordingDao {
    @Insert
    suspend fun insert(recording: Recording): Long

    @Update
    suspend fun update(recording: Recording)

    @Query("SELECT * FROM recordings ORDER BY timestamp DESC")
    suspend fun getAll(): List<Recording>

    @Query("SELECT * FROM recordings WHERE category = :category ORDER BY timestamp DESC")
    suspend fun getByCategory(category: String): List<Recording>

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getById(id: Long): Recording?

    @Query("UPDATE recordings SET transcript = :transcript, transcriptionStatus = 'DONE' WHERE id = :id")
    suspend fun updateTranscript(id: Long, transcript: String)

    @Query("UPDATE recordings SET summary = :summary WHERE id = :id")
    suspend fun updateSummary(id: Long, summary: String)

    @Query("UPDATE recordings SET transcriptionStatus = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query("SELECT * FROM recordings WHERE transcript LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    suspend fun search(query: String): List<Recording>
}

@Dao
interface TranscriptLineDao {
    @Insert
    suspend fun insertAll(lines: List<TranscriptLine>)

    @Query("SELECT * FROM transcript_lines WHERE recordingId = :recordingId ORDER BY startTime ASC")
    suspend fun getForRecording(recordingId: Long): List<TranscriptLine>

    @Query("DELETE FROM transcript_lines WHERE recordingId = :recordingId")
    suspend fun deleteForRecording(recordingId: Long)
}
