package com.runanywhere.startup_hackathon20

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AudioCaptureManager(private val context: Context) {
    companion object {
        const val SAMPLE_RATE = 16000
        const val FRAME_DURATION_MS = 20
        const val FRAME_SIZE = (SAMPLE_RATE * FRAME_DURATION_MS) / 1000
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    val frameChannel = Channel<ShortArray>(Channel.UNLIMITED)
    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing
    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel
    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    fun start(scope: CoroutineScope): Boolean {
        if (!hasPermission()) return false
        if (_isCapturing.value) return true
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )
        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            return false
        }
        val bufferSize = maxOf(minBufferSize, FRAME_SIZE * 2 * 4)
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord?.release()
                audioRecord = null
                return false
            }
            audioRecord?.startRecording()
            _isCapturing.value = true
            captureJob = scope.launch(Dispatchers.IO) {
                val frameBuffer = ShortArray(FRAME_SIZE)
                var frameCount = 0
                var zeroReadCount = 0
                while (isActive && _isCapturing.value) {
                    val samplesRead = audioRecord?.read(frameBuffer, 0, FRAME_SIZE) ?: -1
                    frameCount++
                    if (samplesRead > 0) {
                        zeroReadCount = 0
                        val frameCopy = frameBuffer.copyOf(samplesRead)
                        frameChannel.trySend(frameCopy)
                        _audioLevel.value = calculateRMS(frameCopy)
                    } else {
                        zeroReadCount++
                        if (zeroReadCount > 10) {
                            Thread.sleep(1)
                        }
                    }
                }
            }
            return true
        } catch (e: SecurityException) {
            return false
        }
    }
    fun stop() {
        try {
            _isCapturing.value = false
            captureJob?.cancel()
            captureJob = null
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {}
    }
    private fun calculateRMS(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (sample in samples) {
            sum += sample.toDouble() * sample.toDouble()
        }
        return kotlin.math.sqrt(sum / samples.size).toFloat() / Short.MAX_VALUE
    }
}
