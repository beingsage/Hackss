package com.runanywhere.startup_hackathon20

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sqrt

class AdvancedVADEngine {
    companion object {
        const val FRAME_MS = 20
        const val VAD_WINDOW_MS = 200
        const val SILENCE_TIMEOUT_MS = 250
        const val HYSTERESIS_ON = 3
        const val HYSTERESIS_OFF = 6
        const val ALPHA = 0.3f
        const val THRESH_MULT = 1.8f
        const val ABS_MIN_THRESHOLD = 1e-4f
        const val ZCR_THRESHOLD = 0.15f
        const val SLOPE_THRESHOLD = 0.0005f
        const val LOOKAHEAD_MS = 20
        const val HARMONICITY_THRESHOLD = 0.4f
    }
    enum class State {
        IDLE, SPEAKING, SILENCE, END_OF_UTTERANCE
    }
    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state
    private var energyAvg = 0f
    private var voiceFrameCount = 0
    private var nonVoiceFrameCount = 0
    private var silenceStartTime = 0L
    private val frameHistory = ArrayDeque<Boolean>(VAD_WINDOW_MS / FRAME_MS)
    private val energyHistory = ArrayDeque<Float>(10)
    private var slopeDetectedSpeech = false
    private var neuralVADEnabled = false
    fun processFrame(samples: ShortArray, lookaheadSamples: ShortArray? = null): State {
        val energy = computeRMS(samples)
        val zcr = computeZCR(samples)
        energyHistory.addLast(energy)
        if (energyHistory.size > 10) energyHistory.removeFirst()
        val slope = computeEnergySlope()
        if (slope > SLOPE_THRESHOLD && _state.value != State.SPEAKING) {
            slopeDetectedSpeech = true
        }
        energyAvg = ALPHA * energy + (1 - ALPHA) * energyAvg
        val threshold = maxOf(energyAvg * THRESH_MULT, ABS_MIN_THRESHOLD)
        val energyVoice = energy > threshold
        val zcrVoice = zcr > ZCR_THRESHOLD
        val slopeVoice = slopeDetectedSpeech
        val neuralVoice = if (neuralVADEnabled) runNeuralVAD(samples) else false
        val isVoice = energyVoice || zcrVoice || slopeVoice || neuralVoice
        if (frameHistory.size >= VAD_WINDOW_MS / FRAME_MS) {
            frameHistory.removeFirst()
        }
        frameHistory.addLast(isVoice)
        if (isVoice) {
            voiceFrameCount++
            nonVoiceFrameCount = 0
            slopeDetectedSpeech = false
        } else {
            nonVoiceFrameCount++
            voiceFrameCount = 0
        }
        val currentState = _state.value
        val newState = when {
            currentState != State.SPEAKING && voiceFrameCount >= HYSTERESIS_ON -> {
                silenceStartTime = 0L
                State.SPEAKING
            }
            currentState == State.SPEAKING && nonVoiceFrameCount >= HYSTERESIS_OFF -> {
                State.END_OF_UTTERANCE
            }
            currentState == State.SILENCE && nonVoiceFrameCount < HYSTERESIS_OFF -> {
                silenceStartTime = 0L
                State.SPEAKING
            }
            currentState == State.END_OF_UTTERANCE -> State.IDLE
            else -> currentState
        }
        _state.value = newState
        return newState
    }
    private fun computeEnergySlope(): Float {
        if (energyHistory.size < 3) return 0f
        val recent = energyHistory.takeLast(3)
        val slope = (recent.last() - recent.first()) / recent.size
        return slope
    }
    private fun runNeuralVAD(samples: ShortArray): Boolean {
        return false
    }
    fun enableNeuralVAD(enabled: Boolean) {
        neuralVADEnabled = enabled
    }
    fun reset() {
        _state.value = State.IDLE
        energyAvg = 0f
        voiceFrameCount = 0
        nonVoiceFrameCount = 0
        silenceStartTime = 0L
        frameHistory.clear()
        energyHistory.clear()
        slopeDetectedSpeech = false
    }
    private fun computeRMS(samples: ShortArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (sample in samples) {
            val normalized = sample.toDouble() / Short.MAX_VALUE
            sum += normalized * normalized
        }
        return sqrt(sum / samples.size).toFloat()
    }
    private fun computeZCR(samples: ShortArray): Float {
        if (samples.size < 2) return 0f
        var crossings = 0
        for (i in 1 until samples.size) {
            if ((samples[i] >= 0 && samples[i - 1] < 0) || (samples[i] < 0 && samples[i - 1] >= 0)) {
                crossings++
            }
        }
        return crossings.toFloat() / (samples.size - 1)
    }
    fun getVoiceConfidence(): Float {
        val voiceFrames = frameHistory.count { it }
        return voiceFrames.toFloat() / maxOf(frameHistory.size, 1)
    }
}
