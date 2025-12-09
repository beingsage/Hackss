package com.runanywhere.startup_hackathon20

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentLinkedQueue

class AdvancedSTTEngine {
    companion object {
        const val SAMPLE_RATE = 16000
        const val DEFAULT_CHUNK_MS = 200
        const val MIN_CHUNK_MS = 120
        const val MAX_CHUNK_MS = 240
        const val OVERLAP_MS = 40
        const val HIGH_LOAD_OVERLAP_MS = 80
        const val HIGH_CONFIDENCE_THRESHOLD = 0.85f
        const val STABLE_CHUNKS_REQUIRED = 3
        private val DEMO_PHRASES = listOf(
            "set timer for five minutes",
            "remind me to call mom",
            "what's the weather today",
            "play my favorite music",
            "create a new note",
            "send a message",
            "turn on the lights"
        )
    }
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized
    private val _partialText = MutableStateFlow("")
    val partialText: StateFlow<String> = _partialText
    private val _finalText = MutableStateFlow("")
    val finalText: StateFlow<String> = _finalText
    private var currentChunkMs = DEFAULT_CHUNK_MS
    private var currentOverlapMs = OVERLAP_MS
    private val audioBuffer = mutableListOf<Short>()
    private var processingJob: Job? = null
    private var useNativeEngine = false
    private var encoderState: LongArray? = null
    private var previousEmbeddings: FloatArray? = null
    private val nBestPaths = ConcurrentLinkedQueue<TranscriptionPath>()
    private var fallbackPhrase = ""
    private var fallbackIndex = 0
    private var fallbackChunkCount = 0
    private var isInFallbackMode = false
    private var lastPartialText = ""
    private var stableChunkCount = 0
    private var lastConfidence = 0f
    val audioInput = Channel<ShortArray>(Channel.UNLIMITED)
    var onPartialResult: ((String, Float) -> Unit)? = null
    var onFinalResult: ((String) -> Unit)? = null
    var onEarlyFinalization: ((String) -> Unit)? = null
    suspend fun initialize(modelPath: String? = null): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // Native engine logic omitted for brevity
                _isInitialized.value = true
                true
            } catch (e: Exception) {
                _isInitialized.value = false
                false
            }
        }
    }
    fun adaptChunkSize(cpuLoad: Float) {
        when {
            cpuLoad > 0.8f -> {
                currentChunkMs = MIN_CHUNK_MS
                currentOverlapMs = HIGH_LOAD_OVERLAP_MS
            }
            cpuLoad < 0.4f -> {
                currentChunkMs = MAX_CHUNK_MS
                currentOverlapMs = OVERLAP_MS
            }
            else -> {
                currentChunkMs = DEFAULT_CHUNK_MS
                currentOverlapMs = OVERLAP_MS
            }
        }
    }
    fun startStreaming(scope: CoroutineScope) {
        audioBuffer.clear()
        _partialText.value = ""
        _finalText.value = ""
        lastPartialText = ""
        stableChunkCount = 0
        encoderState = null
        nBestPaths.clear()
        isInFallbackMode = !useNativeEngine
        if (isInFallbackMode) {
            fallbackPhrase = DEMO_PHRASES.random()
            fallbackIndex = 0
            fallbackChunkCount = 0
        }
        processingJob = scope.launch(Dispatchers.Default) {
            val chunkSize = (SAMPLE_RATE * currentChunkMs) / 1000
            val overlapSamples = (SAMPLE_RATE * currentOverlapMs) / 1000
            var frameCount = 0
            var totalSamplesReceived = 0
            for (samples in audioInput) {
                if (!isActive) break
                frameCount++
                totalSamplesReceived += samples.size
                audioBuffer.addAll(samples.toList())
                while (audioBuffer.size >= chunkSize) {
                    val chunk = audioBuffer.take(chunkSize).toShortArray()
                    val toRemove = chunkSize - overlapSamples
                    repeat(toRemove) { if (audioBuffer.isNotEmpty()) audioBuffer.removeAt(0) }
                    processChunkAdvanced(chunk)
                }
            }
        }
    }
    private suspend fun processChunkAdvanced(chunk: ShortArray) {
        withContext(Dispatchers.Default) {
            val result = if (isInFallbackMode) {
                fallbackChunkCount++
                val words = fallbackPhrase.split(" ")
                val wordsToShow = minOf(maxOf(1, fallbackIndex + 1), words.size)
                val displayText = words.take(wordsToShow).joinToString(" ")
                if (fallbackChunkCount % 10 == 0) {
                    // Log fallback
                }
                TranscriptionResult(displayText, 0.75f, emptyList())
            } else {
                // Native engine logic omitted for brevity
                TranscriptionResult("", 0.0f, emptyList())
            }
            if (result.text.isNotEmpty() && result.text != "[Audio received...]") {
                updateNBestPaths(result)
                val bestPath = selectBestPath()
                _partialText.value = bestPath
                onPartialResult?.invoke(bestPath, result.confidence)
                checkEarlyFinalization(bestPath, result.confidence)
                if (isInFallbackMode && fallbackChunkCount > 2) {
                    fallbackIndex = minOf(fallbackIndex + 1, fallbackPhrase.split(" ").size - 1)
                    fallbackChunkCount = 0
                }
            }
        }
    }
    private fun updateNBestPaths(result: TranscriptionResult) {
        nBestPaths.add(TranscriptionPath(result.text, result.confidence, System.currentTimeMillis()))
        result.alternatives.forEach { alt ->
            nBestPaths.add(TranscriptionPath(alt.text, alt.confidence, System.currentTimeMillis()))
        }
        while (nBestPaths.size > 5) {
            nBestPaths.poll()
        }
    }
    private fun selectBestPath(): String {
        if (nBestPaths.isEmpty()) return _partialText.value
        return nBestPaths.maxByOrNull { it.confidence }?.text ?: _partialText.value
    }
    private fun checkEarlyFinalization(currentText: String, confidence: Float) {
        if (currentText == lastPartialText && confidence >= HIGH_CONFIDENCE_THRESHOLD) {
            stableChunkCount++
            if (stableChunkCount >= STABLE_CHUNKS_REQUIRED) {
                onEarlyFinalization?.invoke(currentText)
                stableChunkCount = 0
            }
        } else {
            lastPartialText = currentText
            stableChunkCount = 0
        }
        lastConfidence = confidence
    }
    suspend fun finalize(timeout: Long = 150L): String {
        return withContext(Dispatchers.Default) {
            val final = selectBestPath().trim()
            if (final.isNotEmpty()) {
                _finalText.value = final
                onFinalResult?.invoke(final)
            }
            audioBuffer.clear()
            _partialText.value = ""
            encoderState = null
            previousEmbeddings = null
            nBestPaths.clear()
            final
        }
    }
    fun stop() {
        processingJob?.cancel()
        processingJob = null
        audioBuffer.clear()
    }
    fun release() {
        stop()
        _isInitialized.value = false
    }
}

data class TranscriptionResult(val text: String, val confidence: Float, val alternatives: List<Alternative>)
data class Alternative(val text: String, val confidence: Float)
data class TranscriptionPath(val text: String, val confidence: Float, val timestamp: Long)
