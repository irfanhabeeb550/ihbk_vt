package com.habeeb.transcriberecorder.recording

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Singleton bridge: the recording runs inside a foreground Service, but the waveform
 *  lives in the Compose UI. This carries live amplitude and recording state between them. */
object RecorderRepository {
    private val _amplitude = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val amplitude = _amplitude.asSharedFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording = _isRecording.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused = _isPaused.asStateFlow()

    suspend fun updateAmplitude(amp: Int) = _amplitude.emit(amp)
    fun setRecording(value: Boolean) { _isRecording.value = value }
    fun setPaused(value: Boolean) { _isPaused.value = value }
}
