package com.runanywhere.startup_hackathon20

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class AdvancedTTSEngine(private val context: Context) {
    companion object {
        const val SEGMENT_WORDS = 4
        const val UTTERANCE_ID_PREFIX = "echozero_"
        val CACHED_PHRASES = listOf(
            "Okay.", "Done.", "Sure.", "Got it.", "Starting timer.", "Note created.", "What else?", "Here you go.", "No problem."
        )
    }

    private var tts: TextToSpeech? = null
    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking
    private var utteranceCount = 0
    private var synthesisJob: Job? = null
    private var playbackJob: Job? = null
    private val voiceCache = ConcurrentHashMap<String, ByteArray>()
    private val preSynthesisQueue = Channel<String>(Channel.UNLIMITED)
    private val synthesizedQueue = Channel<SynthesizedSegment>(Channel.UNLIMITED)
    val textQueue = Channel<String>(Channel.UNLIMITED)
    var onSpeakingStart: (() -> Unit)? = null
    var onSpeakingEnd: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    fun initialize(onReady: (Boolean) -> Unit = {}) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.let { engine ->
                    val result = engine.setLanguage(Locale.US)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        _isInitialized.value = false
                        onReady(false)
                        return@TextToSpeech
                    }
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            _isSpeaking.value = true
                            onSpeakingStart?.invoke()
                        }
                        override fun onDone(utteranceId: String?) {
                            _isSpeaking.value = false
                            onSpeakingEnd?.invoke()
                        }
                        override fun onError(utteranceId: String?) {
                            _isSpeaking.value = false
                            onError?.invoke("TTS error")
                        }
                    })
                    warmup()
                    preCacheCommonPhrases()
                    _isInitialized.value = true
                    onReady(true)
                }
            } else {
                _isInitialized.value = false
                onReady(false)
            }
        }
    }

    private fun warmup() {
        tts?.speak("", TextToSpeech.QUEUE_FLUSH, null, "warmup")
    }

    private fun preCacheCommonPhrases() {
        CACHED_PHRASES.forEach { phrase ->
            voiceCache[phrase.lowercase()] = ByteArray(0)
        }
    }

    fun startOverlappingSynthesis(scope: CoroutineScope) {
        synthesisJob = scope.launch(Dispatchers.Default) {
            for (text in preSynthesisQueue) {
                if (!isActive) break
                val synthesized = SynthesizedSegment(text, null, System.currentTimeMillis())
                synthesizedQueue.send(synthesized)
            }
        }
        playbackJob = scope.launch(Dispatchers.Main) {
            for (segment in synthesizedQueue) {
                if (!isActive) break
                speakSegmentDirect(segment.text)
            }
        }
    }

    fun speakSpeculative(text: String) {
        val cachedKey = text.lowercase().trim()
        if (voiceCache.containsKey(cachedKey)) {
            speakFromCache(cachedKey)
            return
        }
        if (text.startsWith("Okay") || text.startsWith("Sure") || text.startsWith("Got")) {
            speakSegmentDirect(text.split(" ").take(2).joinToString(" "))
        }
    }

    private fun speakFromCache(cacheKey: String) {
        val phrase = CACHED_PHRASES.find { it.lowercase() == cacheKey }
        phrase?.let { speakSegmentDirect(it) }
    }

    fun speakText(text: String) {
        if (!_isInitialized.value || text.isBlank()) return
        val words = text.split(Regex("\\s+"))
        val segments = words.chunked(SEGMENT_WORDS).map { it.joinToString(" ") }
        if (segments.isNotEmpty()) {
            val utteranceId = "${UTTERANCE_ID_PREFIX}${utteranceCount++}"
            tts?.speak(segments[0], TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            for (i in 1 until segments.size) {
                preSynthesisQueue.trySend(segments[i])
                val id = "${UTTERANCE_ID_PREFIX}${utteranceCount++}"
                tts?.speak(segments[i], TextToSpeech.QUEUE_ADD, null, id)
            }
        }
    }

    private fun speakSegmentDirect(text: String) {
        if (!_isInitialized.value || text.isBlank()) return
        val utteranceId = "${UTTERANCE_ID_PREFIX}${utteranceCount++}"
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
    }

    fun speakPartialIfReady(partialLLMOutput: String) {
        val lower = partialLLMOutput.lowercase()
        val speakablePrefixes = listOf("okay", "sure", "got it", "done", "yes", "no problem")
        for (prefix in speakablePrefixes) {
            if (lower.startsWith(prefix) && !_isSpeaking.value) {
                speakSegmentDirect(prefix.replaceFirstChar { it.uppercase() })
                break
            }
        }
    }

    fun interrupt() {
        tts?.stop()
        _isSpeaking.value = false
        while (textQueue.tryReceive().isSuccess) { }
        while (preSynthesisQueue.tryReceive().isSuccess) { }
        while (synthesizedQueue.tryReceive().isSuccess) { }
    }

    fun setSpeechRate(rate: Float) {
        tts?.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
    }

    fun stop() {
        synthesisJob?.cancel()
        playbackJob?.cancel()
        interrupt()
    }

    fun release() {
        stop()
        tts?.shutdown()
        tts = null
        voiceCache.clear()
        _isInitialized.value = false
    }
}

data class SynthesizedSegment(
    val text: String,
    val audioData: ByteArray?,
    val timestamp: Long
)
