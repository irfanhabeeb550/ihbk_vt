package com.habeeb.transcriberecorder.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Recording::class, TranscriptLine::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao
    abstract fun transcriptLineDao(): TranscriptLineDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "transcribe_recorder.db"
                ).build().also { INSTANCE = it }
            }
    }
}
