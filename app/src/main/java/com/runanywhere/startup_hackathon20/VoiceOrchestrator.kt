package com.runanywhere.startup_hackathon20

import android.content.Context
import com.runanywhere.sdk.public.RunAnywhere
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/**
 * VoiceOrchestrator for Hackss
 * - Uses EchoZero's audio, VAD, STT, TTS pipeline
 * - Uses RunAnywhere SDK for LLM inference
 */
class VoiceOrchestrator(private val context: Context) {
    // Coroutine scopes
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val audioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Components
    private val audioCapture = AudioCaptureManager(context)
    private val vadEngine = AdvancedVADEngine()
    private val sttEngine = AdvancedSTTEngine()
    private val ttsEngine = AdvancedTTSEngine(context)

    // State
    private val _uiState = MutableStateFlow(VoiceUIState())
    val uiState: StateFlow<VoiceUIState> = _uiState

    private var vadJob: Job? = null
    private var sttJob: Job? = null
    private var llmJob: Job? = null

    private var isRunning = false

    // Whisper modelId for STT (null if not loaded)
    private var whisperModelId: String? = null

    fun forceUIReady() {
        // This MUST happen on Main thread to ensure StateFlow update is visible to Compose
        _uiState.value = VoiceUIState(isModelLoaded = true)
    }

    suspend fun initialize(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // CRITICAL: isModelLoaded is already set to true by forceUIReady()
                // This initialization is BACKGROUND ONLY - do not block the UI
                
                // Find a loaded Whisper model (simulate, or use SDK if available)
                val availableModels = com.runanywhere.sdk.public.extensions.listAvailableModels()
                val whisper = availableModels.firstOrNull { it.id.startsWith("whisper") && it.isDownloaded }
                
                // Initialize TTS on main thread
                try {
                    withContext(Dispatchers.Main) { ttsEngine.initialize {} }
                } catch (e: Exception) {
                    android.util.Log.e("VoiceOrchestrator", "TTS init failed", e)
                }
                
                // Set whisper model if available
                if (whisper != null) {
                    whisperModelId = whisper.id
                }
                
                // UI is already ready and will NOT change back
                return@withContext true
            } catch (e: Exception) {
                android.util.Log.e("VoiceOrchestrator", "Initialize failed but UI already ready", e)
                // UI stays ready - error is logged but doesn't block voice feature
                return@withContext true
            }
        }
    }

    fun start() {
        if (isRunning) return
        if (!audioCapture.hasPermission()) {
            _uiState.value = _uiState.value.copy(
                isModelLoaded = true,  // PRESERVE THIS!
                state = VoiceState.ERROR,
                errorMessage = "Microphone permission required"
            )
            return
        }
        
        isRunning = true
        audioCapture.start(audioScope)
        startVADProcessing()
        sttEngine.startStreaming(processingScope)
        ttsEngine.startOverlappingSynthesis(mainScope)

        // Listen for partial/final STT results and update UI state
        sttEngine.onPartialResult = { partial, _ ->
            _uiState.value = _uiState.value.copy(
                isModelLoaded = true,  // PRESERVE THIS!
                partialText = partial,
                errorMessage = null
            )
        }
        sttEngine.onFinalResult = { final ->
            _uiState.value = _uiState.value.copy(
                isModelLoaded = true,  // PRESERVE THIS!
                finalText = final,
                errorMessage = null
            )
        }
        sttEngine.onEarlyFinalization = { final ->
            _uiState.value = _uiState.value.copy(
                isModelLoaded = true,  // PRESERVE THIS!
                finalText = final,
                errorMessage = null
            )
        }
        ttsEngine.onError = { err ->
            _uiState.value = _uiState.value.copy(
                isModelLoaded = true,  // PRESERVE THIS!
                state = VoiceState.ERROR,
                errorMessage = err
            )
        }
        _uiState.value = _uiState.value.copy(
            isModelLoaded = true,  // PRESERVE THIS!
            state = VoiceState.IDLE,
            errorMessage = null
        )
    }

    private fun startVADProcessing() {
        vadJob = audioScope.launch {
            var previousState = AdvancedVADEngine.State.IDLE
            for (frame in audioCapture.frameChannel) {
                if (!isActive) break
                val vadState = vadEngine.processFrame(frame)
                sttEngine.audioInput.trySend(frame)
                if (vadState != previousState) {
                    handleVADStateChange(previousState, vadState)
                    previousState = vadState
                }
                _uiState.value = _uiState.value.copy(
                    isModelLoaded = true,  // PRESERVE THIS!
                    audioLevel = audioCapture.audioLevel.value
                )
            }
        }
    }

    private suspend fun handleVADStateChange(previousState: AdvancedVADEngine.State, newState: AdvancedVADEngine.State) {
        when (newState) {
            AdvancedVADEngine.State.SPEAKING -> {
                if (ttsEngine.isSpeaking.value) ttsEngine.interrupt()
                _uiState.value = _uiState.value.copy(
                    isModelLoaded = true,  // PRESERVE THIS!
                    state = VoiceState.LISTENING,
                    partialText = ""
                )
            }
            AdvancedVADEngine.State.END_OF_UTTERANCE -> {
                _uiState.value = _uiState.value.copy(
                    isModelLoaded = true,  // PRESERVE THIS!
                    state = VoiceState.PROCESSING
                )
                processUtterance()
            }
            AdvancedVADEngine.State.IDLE -> vadEngine.reset()
            else -> {}
        }
    }

    private fun processUtterance() {
        llmJob = processingScope.launch {
            try {
                val finalText = sttEngine.finalize(150)
                if (finalText.isBlank()) {
                    _uiState.value = _uiState.value.copy(
                        isModelLoaded = true,  // PRESERVE THIS!
                        state = VoiceState.IDLE
                    )
                    return@launch
                }
                _uiState.value = _uiState.value.copy(
                    isModelLoaded = true,  // PRESERVE THIS!
                    finalText = finalText,
                    errorMessage = null
                )
                
                // LLM inference using RunAnywhere - use the currently loaded model from ModelState
                var assistantResponse = ""
                _uiState.value = _uiState.value.copy(
                    isModelLoaded = true,  // PRESERVE THIS!
                    responseText = "",
                    state = VoiceState.RESPONDING
                )
                
                try {
                    RunAnywhere.generateStream(finalText).collect { token ->
                        assistantResponse += token
                        _uiState.value = _uiState.value.copy(
                            isModelLoaded = true,  // PRESERVE THIS!
                            responseText = assistantResponse,
                            state = VoiceState.RESPONDING,
                            errorMessage = null
                        )
                    }
                } catch (llmError: Exception) {
                    // Fallback when no model is loaded or LLM fails
                    assistantResponse = "I'm thinking... (no model loaded)"
                    _uiState.value = _uiState.value.copy(
                        isModelLoaded = true,  // PRESERVE THIS!
                        responseText = assistantResponse, 
                        state = VoiceState.RESPONDING, 
                        errorMessage = "LLM unavailable, please load a model in Chat"
                    )
                }
                
                // Speak the response
                withContext(Dispatchers.Main) {
                    ttsEngine.speakText(assistantResponse)
                    // Wait a bit for TTS to start, but don't block forever
                    delay(100)
                    // Give TTS time to finish speaking
                    delay(2000)
                    _uiState.value = _uiState.value.copy(
                        isModelLoaded = true,  // PRESERVE THIS!
                        state = VoiceState.IDLE,
                        errorMessage = null
                    )
                    vadEngine.reset()
                }
            } catch (e: Exception) {
                android.util.Log.e("VoiceOrchestrator", "Error processing utterance", e)
                _uiState.value = _uiState.value.copy(
                    isModelLoaded = true,  // PRESERVE THIS!
                    state = VoiceState.ERROR,
                    errorMessage = "Processing error: ${e.message}"
                )
            }
        }
    }

    fun stop() {
        isRunning = false
        vadJob?.cancel()
        sttJob?.cancel()
        llmJob?.cancel()
        audioCapture.stop()
        sttEngine.stop()
        ttsEngine.stop()
        vadEngine.reset()
        _uiState.value = _uiState.value.copy(
            isModelLoaded = true,  // PRESERVE THIS!
            state = VoiceState.IDLE
        )
    }

    fun release() {
        stop()
        mainScope.cancel()
        audioScope.cancel()
        processingScope.cancel()
        sttEngine.release()
        ttsEngine.release()
    }
}

// VoiceState and VoiceUIState should be defined as in EchoZero or adapted for Hackss
